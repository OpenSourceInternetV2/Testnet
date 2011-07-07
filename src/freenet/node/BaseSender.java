package freenet.node;

import java.util.HashMap;
import java.util.HashSet;

import freenet.io.comm.ByteCounter;
import freenet.io.comm.DMT;
import freenet.io.comm.DisconnectedException;
import freenet.io.comm.Message;
import freenet.io.comm.MessageFilter;
import freenet.io.comm.NotConnectedException;
import freenet.io.comm.PeerContext;
import freenet.io.comm.SlowAsyncMessageFilterCallback;
import freenet.keys.Key;
import freenet.keys.NodeCHK;
import freenet.keys.NodeSSK;
import freenet.node.PeerNode.RequestLikelyAcceptedState;
import freenet.node.PeerNode.SlotWaiter;
import freenet.node.PeerNode.SlotWaiterFailedException;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.TimeUtil;
import freenet.support.Logger.LogLevel;
import freenet.support.io.NativeThread;

/** Base class for request and insert senders.
 * Mostly concerned with what happens *before and up to* we get the Accepted.
 * After that it hands over to the child class. */
public abstract class BaseSender implements ByteCounter {
	
    private static volatile boolean logMINOR;
    static {
	Logger.registerLogThresholdCallback(new LogThresholdCallback(){
		@Override
		public void shouldUpdate(){
			logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
		}
	});
    }
    
    final boolean realTimeFlag;
    final Key key;
    /** The source of this request if any - purely so we can avoid routing to it */
    final PeerNode source;
    final double target;
    final boolean isSSK;
    protected final short origHTL;
    final Node node;
    protected final long startTime;
    final long uid;
    
    BaseSender(Key key, boolean realTimeFlag, PeerNode source, Node node, short htl, long uid) {
    	if(key.getRoutingKey() == null) throw new NullPointerException();
    	startTime = System.currentTimeMillis();
    	this.uid = uid;
    	this.key = key;
    	this.realTimeFlag = realTimeFlag;
    	this.node = node;
    	this.source = source;
        target = key.toNormalizedDouble();
        this.isSSK = key instanceof NodeSSK;
        assert(isSSK || key instanceof NodeCHK);
        this.htl = htl;
        this.origHTL = htl;
    }
    
	protected abstract Message createDataRequest();

    protected PeerNode lastNode;

    public synchronized PeerNode routedLast() {
    	return lastNode;
    }
    
    protected HashSet<PeerNode> nodesRoutedTo = new HashSet<PeerNode>();
    
    private long timeSentRequest;
    
    protected synchronized int timeSinceSent() {
    	return (int) (System.currentTimeMillis() - timeSentRequest);
    }
    
    protected boolean hasForwarded;
    
    protected int gotMessages;
    protected String lastMessage;
    
    protected short htl;
    
    protected int rejectOverloads;
    
	protected int routeAttempts = 0;
	
    private HashMap<PeerNode, Integer> softRejectCount;
    
    protected boolean innerRouteRequestsOld(PeerNode next, UIDTag origTag) {
        
        synchronized(this) {
        	lastNode = next;
        }
		
        if(logMINOR) Logger.minor(this, "Routing request to "+next);
        nodesRoutedTo.add(next);
        
        Message req = createDataRequest();
        
        // Not possible to get an accurate time for sending, guaranteed to be not later than the time of receipt.
        // Why? Because by the time the sent() callback gets called, it may already have been acked, under heavy load.
        // So take it from when we first started to try to send the request.
        // See comments below when handling FNPRecentlyFailed for why we need this.
        synchronized(this) {
        	timeSentRequest = System.currentTimeMillis();
        }
		
        origTag.addRoutedTo(next, false);
        
        try {
        	//This is the first contact to this node, it is more likely to timeout
			/*
			 * using sendSync could:
			 *   make ACCEPTED_TIMEOUT more accurate (as it is measured from the send-time),
			 *   use a lot of our time that we have to fulfill this request (simply waiting on the send queue, or longer if the node just went down),
			 * using sendAsync could:
			 *   make ACCEPTED_TIMEOUT much more likely,
			 *   leave many hanging-requests/unclaimedFIFO items,
			 *   potentially make overloaded peers MORE overloaded (we make a request and promptly forget about them).
			 * 
			 * Don't use sendAsync().
			 */
        	next.sendSync(req, this, realTimeFlag);
        	next.reportRoutedTo(key.toNormalizedDouble(), source == null, realTimeFlag);
			node.peers.incrementSelectionSamples(System.currentTimeMillis(), next);
        } catch (NotConnectedException e) {
        	Logger.minor(this, "Not connected");
        	next.noLongerRoutingTo(origTag, false);
        	return true;
        } catch (SyncSendWaitedTooLongException e) {
        	Logger.error(this, "Failed to send "+req+" to "+next+" in a reasonable time.");
        	next.noLongerRoutingTo(origTag, false);
        	// Try another node.
        	return true;
		}
        
        synchronized(this) {
        	hasForwarded = true;
        }
        
loadWaiterLoop:
        while(true) {
        	DO action = waitForAccepted(null, next, origTag);
        	// Here FINISHED means accepted, WAIT means try again (soft reject).
        	if(action == DO.WAIT) {
				//retriedForLoadManagement = true;
        		continue loadWaiterLoop;
        	} else if(action == DO.NEXT_PEER) {
        		return true;
        	} else { // FINISHED => accepted
        		break;
        	}
        } // loadWaiterLoop
        
        if(logMINOR) Logger.minor(this, "Got Accepted");
        
        // Otherwise, must be Accepted
        
        gotMessages = 0;
        lastMessage = null;
        
        onAccepted(next);
        
        return false;
	}

    /**
     * @param next
     * @return True to try another peer.
     */
    protected boolean innerRouteRequestsNew(PeerNode next, UIDTag origTag) {
        
    	NodeStats.RequestType type =
    		isSSK ? NodeStats.RequestType.SSK_REQUEST : NodeStats.RequestType.CHK_REQUEST;
    	
        int tryCount = 0;
        
        long startedTryingPeer = System.currentTimeMillis();
        
        boolean waitedForLoadManagement = false;
        boolean retriedForLoadManagement = false;
        
        SlotWaiter waiter = null;
        
        PeerNode lastNext = null;
        RequestLikelyAcceptedState lastExpectedAcceptState = null;
        RequestLikelyAcceptedState expectedAcceptState = null;
        
        boolean addedExtraNode = false;
        
    loadWaiterLoop:
    	while(true) {
    		if(logMINOR) Logger.minor(this, "Going around loop");
    		
    		if(next == null) {
				next = node.peers.closerPeer(source, nodesRoutedTo, target, true, node.isAdvancedModeEnabled(), -1, null,
						key, htl, 0, source == null, realTimeFlag, true);
				if(next == null) {
					if (logMINOR && rejectOverloads>0)
						Logger.minor(this, "no more peers, but overloads ("+rejectOverloads+"/"+routeAttempts+" overloaded)");
					// Backtrack
					rnf();
					return false;
				}
    		}
        
   			expectedAcceptState = 
   				next.outputLoadTracker(realTimeFlag).tryRouteTo(origTag, RequestLikelyAcceptedState.LIKELY, false);
    		
    		if(expectedAcceptState == RequestLikelyAcceptedState.UNKNOWN) {
    			// No stats, old style, just go for it.
    			// This can happen both when talking to an old node and when we've just connected, but should not be the case for long enough to be a problem.
    			if(logMINOR) Logger.minor(this, "No load stats for "+next);
    		} else {
    			if(expectedAcceptState != null) {
    				if(logMINOR)
    					Logger.minor(this, "Predicted accept state for "+this+" : "+expectedAcceptState+" realtime="+realTimeFlag);
    				// FIXME sanity check based on new data. Backoff if not plausible.
    				// FIXME recalculate with broader check, allow a few percent etc.
    				if(lastNext == next && lastExpectedAcceptState == RequestLikelyAcceptedState.GUARANTEED && 
    						(expectedAcceptState == RequestLikelyAcceptedState.GUARANTEED)) {
    					// We routed it, thinking it was GUARANTEED.
    					// It was rejected, and as far as we know it's still GUARANTEED. :(
    					Logger.error(this, "Rejected overload (last time) yet expected state was "+lastExpectedAcceptState+" is now "+expectedAcceptState+" from "+next.shortToString()+" ("+next.getVersionNumber()+")");
    					next.enterMandatoryBackoff("Mandatory:RejectedGUARANTEED", realTimeFlag);
    					next.noLongerRoutingTo(origTag, false);
    					expectedAcceptState = null;
    					next = null;
    					// Will reroute.
    					continue;
    				}
    			}
    			
				int canWaitFor = 1;
				
    			if(expectedAcceptState == null) {
    				if(logMINOR)
    					Logger.minor(this, "Cannot send to "+next+" realtime="+realTimeFlag);
    				waitedForLoadManagement = true;
    				if(waiter == null)
    					waiter = PeerNode.createSlotWaiter(origTag, type, false, realTimeFlag);
    				if(next != null) {
    					if(!waiter.addWaitingFor(next)) {
    						next = null;
    						continue;
    						// Will be rerouted.
    						// This is essential to avoid adding the same bogus node again and again.
    						// This is only an issue with next. Hence the other places we route explicitly so there is no risk as they won't return the same node repeatedly after it is no longer routable.
    					}
    				}
				
    	            if(next.isLowCapacity(realTimeFlag)) {
    	            	if(waiter.waitingForCount() == 1) {
    	            		canWaitFor++;
    	            		// Wait for another one if the first is low capacity.
        					// Nodes we were waiting for that then became backed off will have been removed from the list.
        					HashSet<PeerNode> exclude = waiter.waitingForList();
        					exclude.addAll(nodesRoutedTo);
    	            		PeerNode alsoWaitFor =
    	            			node.peers.closerPeer(source, exclude, target, true, node.isAdvancedModeEnabled(), -1, null,
    	            					key, htl, 0, source == null, realTimeFlag, true);
    	            		if(alsoWaitFor != null) {
    	            			waiter.addWaitingFor(alsoWaitFor);
    	            			// We do not need to check the return value here.
    	            			// We will not reuse alsoWaitFor if it is disconnected etc.
    	            			if(logMINOR) Logger.minor(this, "Waiting for "+next+" and "+alsoWaitFor+" on "+waiter+" because realtime");
    	            			PeerNode matched;
								try {
									matched = waiter.waitForAny(0);
								} catch (SlotWaiterFailedException e) {
									if(logMINOR) Logger.minor(this, "Rerouting as slot waiter failed...");
									continue;
								}
    	            			if(matched != null) {
    	            				expectedAcceptState = waiter.getAcceptedState();
    	            				next = matched;
    	            				if(logMINOR) Logger.minor(this, "Matched "+matched+" with "+expectedAcceptState);
    	            			}
    	            		}
    	            	}
    	            }
    			}
    			
    			if(realTimeFlag) canWaitFor++;
    			// Skip it and go straight to rerouting if no next, as above.
    			if(expectedAcceptState == null && waiter.waitingForCount() <= canWaitFor) {
            		// Wait for another one if realtime.
					// Nodes we were waiting for that then became backed off will have been removed from the list.
					HashSet<PeerNode> exclude = waiter.waitingForList();
					exclude.addAll(nodesRoutedTo);
            		PeerNode alsoWaitFor =
            			node.peers.closerPeer(source, exclude, target, true, node.isAdvancedModeEnabled(), -1, null,
            					key, htl, 0, source == null, realTimeFlag, true);
            		if(alsoWaitFor != null) {
            			waiter.addWaitingFor(alsoWaitFor);
            			// We do not need to check the return value here.
            			// We will not reuse alsoWaitFor if it is disconnected etc.
            			if(logMINOR) Logger.minor(this, "Waiting for "+next+" and "+alsoWaitFor+" on "+waiter+" because realtime");
            			PeerNode matched;
						try {
							matched = waiter.waitForAny(0);
						} catch (SlotWaiterFailedException e) {
							if(logMINOR) Logger.minor(this, "Rerouting as slot waiter failed...");
							continue;
						}
            			if(matched != null) {
            				expectedAcceptState = waiter.getAcceptedState();
            				next = matched;
            				if(logMINOR) Logger.minor(this, "Matched "+matched+" with "+expectedAcceptState);
            			}
            		}
    			}
    			
    			if(addedExtraNode) canWaitFor++;
    			// Skip it and go straight to rerouting if no next, as above.
    			if(expectedAcceptState == null && waiter.waitingForCount() <= canWaitFor) {
            		// Wait for another one if realtime.
					// Nodes we were waiting for that then became backed off will have been removed from the list.
					HashSet<PeerNode> exclude = waiter.waitingForList();
					exclude.addAll(nodesRoutedTo);
            		PeerNode alsoWaitFor =
            			node.peers.closerPeer(source, exclude, target, true, node.isAdvancedModeEnabled(), -1, null,
            					key, htl, 0, source == null, realTimeFlag, true);
            		if(alsoWaitFor != null) {
            			waiter.addWaitingFor(alsoWaitFor);
            			// We do not need to check the return value here.
            			// We will not reuse alsoWaitFor if it is disconnected etc.
            			if(logMINOR) Logger.minor(this, "Waiting for "+next+" and "+alsoWaitFor+" on "+waiter+" because realtime");
            			PeerNode matched;
						try {
							matched = waiter.waitForAny(0);
						} catch (SlotWaiterFailedException e) {
							// Reroute.
							continue;
						}
            			if(matched != null) {
            				expectedAcceptState = waiter.getAcceptedState();
            				next = matched;
            			}
            		}
    			}
    			
    			if(expectedAcceptState == null) {
    				long maxWait = getLongSlotWaiterTimeout();
    				// After waitForAny() it will be null, it is all cleared.
    				if(!addedExtraNode) {
    					// Can add another one if it's taking ages.
    					// However after adding it once, we will wait for as long as it takes.
    					maxWait = getShortSlotWaiterTimeout();
    				}
    				PeerNode waited;
					try {
						waited = waiter.waitForAny(maxWait);
					} catch (SlotWaiterFailedException e) {
						// Failed. Reroute.
						continue;
					}
    				if(waited == null) {
    					// Timed out, or not waiting for anything, not failed.
    					if(logMINOR) Logger.minor(this, "Timed out waiting for a peer to accept "+this+" on "+waiter);
    					
    					if(addedExtraNode) {
    						// Backtrack
    						rnf();
    						return false;
    					} else {
    						addedExtraNode = true;
    						continue;
    					}
    				} else {
    					next = waited;
    					expectedAcceptState = waiter.getAcceptedState();
    					long endTime = System.currentTimeMillis();
    					if(logMINOR) Logger.minor(this, "Sending to "+next+ " after waited for "+TimeUtil.formatTime(endTime-startTime)+" realtime="+realTimeFlag);
    					expectedAcceptState = waiter.getAcceptedState();
    				}
    				
    			}
    			assert(expectedAcceptState != null);
    			lastExpectedAcceptState = expectedAcceptState;
    			lastNext = next;
				if(logMINOR)
					Logger.minor(this, "Leaving new load management big block: Predicted accept state for "+this+" : "+expectedAcceptState+" realtime="+realTimeFlag+" for "+next);
    			// FIXME only report for routing accuracy purposes at this point, not in closerPeer().
    			// In fact, we should report only after Accepted.
    		}
    		if(logMINOR) Logger.minor(this, "Routing to "+next);
    		
    		synchronized(this) {
    			lastNode = next;
    		}
    		
    		if(logMINOR) Logger.minor(this, "Routing request to "+next+" realtime="+realTimeFlag);
    		nodesRoutedTo.add(next);
    		
    		Message req = createDataRequest();
    		
    		// Not possible to get an accurate time for sending, guaranteed to be not later than the time of receipt.
    		// Why? Because by the time the sent() callback gets called, it may already have been acked, under heavy load.
    		// So take it from when we first started to try to send the request.
    		// See comments below when handling FNPRecentlyFailed for why we need this.
    		synchronized(this) {
    			timeSentRequest = System.currentTimeMillis();
    		}
    		
    		origTag.addRoutedTo(next, false);
    		
    		tryCount++;
    		
    		try {
    			//This is the first contact to this node, it is more likely to timeout
    			/*
    			 * using sendSync could:
    			 *   make ACCEPTED_TIMEOUT more accurate (as it is measured from the send-time),
    			 *   use a lot of our time that we have to fulfill this request (simply waiting on the send queue, or longer if the node just went down),
    			 * using sendAsync could:
    			 *   make ACCEPTED_TIMEOUT much more likely,
    			 *   leave many hanging-requests/unclaimedFIFO items,
    			 *   potentially make overloaded peers MORE overloaded (we make a request and promptly forget about them).
    			 * 
    			 * Don't use sendAsync().
    			 */
    			if(logMINOR) Logger.minor(this, "Sending "+req+" to "+next);
    			next.reportRoutedTo(key.toNormalizedDouble(), source == null, realTimeFlag);
    			next.sendSync(req, this, realTimeFlag);
    		} catch (NotConnectedException e) {
    			Logger.minor(this, "Not connected");
    			next.noLongerRoutingTo(origTag, false);
    			return true;
            } catch (SyncSendWaitedTooLongException e) {
            	Logger.error(this, "Failed to send "+req+" to "+next+" in a reasonable time.");
            	next.noLongerRoutingTo(origTag, false);
            	// Try another node.
            	continue;
			}
    		
    		synchronized(this) {
    			hasForwarded = true;
    		}
        	
    		if(logMINOR) Logger.minor(this, "Waiting for accepted");
        	DO action = waitForAccepted(expectedAcceptState, next, origTag);
        	// Here FINISHED means accepted, WAIT means try again (soft reject).
        	if(action == DO.WAIT) {
				retriedForLoadManagement = true;
				if(logMINOR) Logger.minor(this, "Retrying");
        		continue loadWaiterLoop;
        	} else if(action == DO.NEXT_PEER) {
				if(logMINOR) Logger.minor(this, "Trying next peer");
				return true;
        	} else { // FINISHED => accepted
				if(logMINOR) Logger.minor(this, "Accepted!");
        		break;
        	}
        } // loadWaiterLoop
        
        long now = System.currentTimeMillis();
        long delta = now-startedTryingPeer;
        // This includes the time for the Accepted to come back, so it can take a while sometimes.
        // So log it at error only if it's really bad.
        logDelta(delta, tryCount, waitedForLoadManagement, retriedForLoadManagement);
        
        if(logMINOR) Logger.minor(this, "Got Accepted");
        
        // Otherwise, must be Accepted
        
        gotMessages = 0;
        lastMessage = null;
        
        onAccepted(next);
        
        return false;
	}
    
    protected abstract long getLongSlotWaiterTimeout();
    
    protected abstract long getShortSlotWaiterTimeout();

	private void logDelta(long delta, int tryCount, boolean waitedForLoadManagement, boolean retriedForLoadManagement) {
        if((delta > 10000 && realTimeFlag) || (delta > 20000 && !realTimeFlag) || tryCount > 2)
        	Logger.error(this, "Took "+tryCount+" tries in "+TimeUtil.formatTime(delta, 2, true)+" waited="+waitedForLoadManagement+" retried="+retriedForLoadManagement+(realTimeFlag ? " (realtime)" : " (bulk)")+((source == null)?" (local)":" (remote)"));
        else if((delta > 1000 && realTimeFlag) || (delta > 10000 && !realTimeFlag) || tryCount > 1)
        	Logger.warning(this, "Took "+tryCount+" tries in "+TimeUtil.formatTime(delta, 2, true)+" waited="+waitedForLoadManagement+" retried="+retriedForLoadManagement+(realTimeFlag ? " (realtime)" : " (bulk)")+((source == null)?" (local)":" (remote)"));            	
        else if(logMINOR && (waitedForLoadManagement || retriedForLoadManagement))
        	Logger.minor(this, "Took "+tryCount+" tries in "+TimeUtil.formatTime(delta, 2, true)+" waited="+waitedForLoadManagement+" retried="+retriedForLoadManagement+(realTimeFlag ? " (realtime)" : " (bulk)")+((source == null)?" (local)":" (remote)"));
        node.nodeStats.reportNLMDelay(delta, realTimeFlag, source == null);
	}

	/** Here FINISHED means accepted, WAIT means try again (soft reject). */
    private DO waitForAccepted(RequestLikelyAcceptedState expectedAcceptState, PeerNode next, UIDTag origTag) {
    	while(true) {
    		
    		Message msg;
    		
    		MessageFilter mf = makeAcceptedRejectedFilter(next, getAcceptedTimeout());
    		
    		try {
    			msg = node.usm.waitFor(mf, this);
    			if(logMINOR) Logger.minor(this, "first part got "+msg);
    		} catch (DisconnectedException e) {
    			Logger.normal(this, "Disconnected from "+next+" while waiting for Accepted on "+uid);
    			next.noLongerRoutingTo(origTag, false);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg == null) {
    			if(logMINOR) Logger.minor(this, "Timeout waiting for Accepted for "+this);
    			// Timeout waiting for Accepted
    			next.localRejectedOverload("AcceptedTimeout", realTimeFlag);
    			forwardRejectedOverload();
    			int t = timeSinceSent();
    			node.failureTable.onFailed(key, next, htl, t, t);
    			// Try next node
    			handleAcceptedRejectedTimeout(next, origTag);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg.getSpec() == DMT.FNPRejectedLoop) {
    			if(logMINOR) Logger.minor(this, "Rejected loop");
    			next.successNotOverload(realTimeFlag);
    			int t = timeSinceSent();
    			node.failureTable.onFailed(key, next, htl, t, t);
    			// Find another node to route to
    			next.noLongerRoutingTo(origTag, false);
    			return DO.NEXT_PEER;
    		}
    		
    		if(msg.getSpec() == DMT.FNPRejectedOverload) {
    			if(logMINOR) Logger.minor(this, "Rejected: overload");
    			// Non-fatal - probably still have time left
    			forwardRejectedOverload();
    			if (msg.getBoolean(DMT.IS_LOCAL)) {
    				
    				if(logMINOR) Logger.minor(this, "Is local");
  
					// FIXME soft rejects, only check then, but don't backoff if sane
					// FIXME recalculate with broader check, allow a few percent etc.
    				
    				if(msg.getSubMessage(DMT.FNPRejectIsSoft) != null && expectedAcceptState != null) {
    					if(logMINOR) Logger.minor(this, "Soft rejection, waiting to resend");
    					if(expectedAcceptState == RequestLikelyAcceptedState.GUARANTEED)
    						// Need to recalculate to be sure this is an error.
    						Logger.normal(this, "Rejected overload yet expected state was "+expectedAcceptState);
    					nodesRoutedTo.remove(next);
    					next.noLongerRoutingTo(origTag, false);
    					if(softRejectCount == null) softRejectCount = new HashMap<PeerNode, Integer>();
    					Integer i = softRejectCount.get(next);
    					if(i == null) softRejectCount.put(next, 1);
    					else {
    						softRejectCount.put(next, i+1);
    						if(i > 3) {
    							Logger.error(this, "Rejected repeatedly ("+i+") by "+next+" : "+this);
    							next.outputLoadTracker(realTimeFlag).setDontSendUnlessGuaranteed();
    						}
    					}
    					return DO.WAIT;
    				}
    				
    				next.localRejectedOverload("ForwardRejectedOverload", realTimeFlag);
    				int t = timeSinceSent();
    				node.failureTable.onFailed(key, next, htl, t, t);
    				if(logMINOR) Logger.minor(this, "Local RejectedOverload, moving on to next peer");
    				// Give up on this one, try another
    				next.noLongerRoutingTo(origTag, false);
    				return DO.NEXT_PEER;
    			}
    			//Could be a previous rejection, the timeout to incur another ACCEPTED_TIMEOUT is minimal...
    			continue;
    		}
    		
    		if(!isAccepted(msg)) {
    			Logger.error(this, "Unrecognized message: "+msg);
    			return DO.NEXT_PEER;
    		}
    		
    		next.resetMandatoryBackoff(realTimeFlag);
    		next.outputLoadTracker(realTimeFlag).clearDontSendUnlessGuaranteed();
    		return DO.FINISHED;
    		
    	}
	}
    
	private void handleAcceptedRejectedTimeout(final PeerNode next,
			final UIDTag origTag) {
		
		origTag.handlingTimeout(next);
		
		int timeout = 60*1000;
		
		MessageFilter mf = makeAcceptedRejectedFilter(next, timeout);
		try {
			node.usm.addAsyncFilter(mf, new SlowAsyncMessageFilterCallback() {

				@Override
				public void onMatched(Message m) {
					if(m.getSpec() == DMT.FNPRejectedLoop ||
							m.getSpec() == DMT.FNPRejectedOverload) {
						// Ok.
						next.noLongerRoutingTo(origTag, false);
					} else {
						// Accepted. May as well wait for the data, if any.
						onAccepted(next);
					}
				}
				
				@Override
				public boolean shouldTimeout() {
					return false;
				}

				@Override
				public void onTimeout() {
					Logger.error(this, "Fatal timeout waiting for Accepted/Rejected from "+next+" on "+BaseSender.this);
					next.fatalTimeout(origTag, false);
				}

				@Override
				public void onDisconnect(PeerContext ctx) {
					next.noLongerRoutingTo(origTag, false);
				}

				@Override
				public void onRestarted(PeerContext ctx) {
					next.noLongerRoutingTo(origTag, false);
				}

				@Override
				public int getPriority() {
					return NativeThread.NORM_PRIORITY;
				}
				
			}, this);
		} catch (DisconnectedException e) {
			next.noLongerRoutingTo(origTag, false);
		}
	}

	protected boolean isAccepted(Message msg) {
		// SSKInsertSender needs an alternative accepted message.
		return msg.getSpec() == DMT.FNPAccepted;
	}

	protected abstract int getAcceptedTimeout();
	
	protected abstract void rnf();
	
	protected abstract void onAccepted(PeerNode next);
	
	protected static enum DO {
    	FINISHED,
    	WAIT,
    	NEXT_PEER
    }

	protected abstract MessageFilter makeAcceptedRejectedFilter(PeerNode next,
			int acceptedTimeout);
	
	protected abstract void forwardRejectedOverload();
	
}
