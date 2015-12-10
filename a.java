package edu.duke.raft;

import java.util.Timer;
import java.util.*;

public class FollowerMode extends RaftMode {	

    private Timer followerTimer;  //follower handle timeout
    private Timer cacheTimer;  //check localcache and foward to leader if it has contents
    
    private int ELECTION_TIMEOUT;
    private List<Entry> localCache;
    private int lastLeader;
	
    
    public void go () 
    {
    	synchronized (mLock) 
    	{
	    localCache=new ArrayList<Entry>();
    		int term = mConfig.getCurrentTerm();
		    System.out.println ("S" + 
				    mID + 
				    "." + 
				    term + 
				    ": switched to follower mode.");
		    //initialization    
		    mConfig.setCurrentTerm(term, 0);
		    RaftResponses.setTerm(term);
		    RaftResponses.clearVotes(term);
		    RaftResponses.clearAppendResponses(term);
		    lastLeader = -1;
		    //calculate random timeout
		    ELECTION_TIMEOUT =  (int)(((double)ELECTION_TIMEOUT_MAX-
		    (double)ELECTION_TIMEOUT_MIN)*Math.random())
		    +ELECTION_TIMEOUT_MIN; 
		    followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
		    // cacheTimer = this.scheduleTimer(10,mID+1);
	    }
	}
    
  // @param candidate’s term
  // @param candidate requesting vote
  // @param index of candidate’s last log entry
  // @param term of candidate’s last log entry
  // @return 0, if server votes for candidate; otherwise, server's
  // current term
    public int requestVote (int candidateTerm,
			  int candidateID,
			  int lastLogIndex,
			  int lastLogTerm) 
    {
    	synchronized (mLock) 
    	{
    		followerTimer.cancel();
    		int term = mConfig.getCurrentTerm ();
    		int vote = term;
    		int voteFor = mConfig.getVotedFor();
    		int lastIndex = mLog.getLastIndex();
    		int lastTerm = mLog.getLastTerm();
    	     	
    		if (candidateTerm < term)//never vote for lower term
    		{
    			followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
    			return vote;
    		}
    		else  //candidateTerm >= term
    		{
    			if (candidateTerm == term &&voteFor != 0)
    			{
    				followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
    				return vote;  //already vote
    			}
    			
    			//candidateTerm == term && voteFor == 0
    			//candidateTerm > term
    			 if (lastLogTerm>mLog.getLastTerm() || lastLogIndex>=lastIndex)
    			 {
    				//System.out.println("server "+mID+" in term "+term+"
    				vote to server "+candidateID);
    				mConfig.setCurrentTerm(candidateTerm, candidateID); 
    				RaftResponses.setTerm(candidateTerm);
				vote = 0;
    			 }
    			else //lower term or same term with lower index
    			{
    				//update my term but not vote for anyone
			        RaftResponses.setTerm(candidateTerm);
    				mConfig.setCurrentTerm(candidateTerm,0); 
    			}
    			followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID); 
    			return vote;
    		}
    	}
    }
  

  // @param leader’s term
  // @param current leader
  // @param index of log entry before entries to append
  // @param term of log entry before entries to append
  // @param entries to append (in order of 0 to append.length-1)
  // @param index of highest committed entry
  // @return 0, if server appended entries; otherwise, server's
  // current term
    public int appendEntries (int leaderTerm,
			    int leaderID,
			    int prevLogIndex,
			    int prevLogTerm,
			    Entry[] entries,
			    int leaderCommit) 
    {
    	synchronized (mLock) 
    	{
    		followerTimer.cancel();
    	        int term = mConfig.getCurrentTerm ();
    		int result = term;
          
    		if (leaderID == mID)
    		{
    			for(int i = 0; i<entries.length;i++)
    			{
    				localCache.add(entries[i]);
    			}
			followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
    			return 0;
    		}
    		else  //append from server
    		{
    			//request from stale leader (not likely), return current term
        		/*if (term>leaderTerm)  
        		{
        			followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID); 
        			return result;  //leader will check return value and turn to follower
        		}*/
        		lastLeader = leaderID;
			RaftResponses.setTerm(leaderTerm);
		        mConfig.setCurrentTerm(leaderTerm, 0);
        		if (entries == null)//is heartbeat
        		{ 
			    // System.out.println("Follower "+mID+"recive heartbeat");
			    term = mConfig.getCurrentTerm();
			    mLastApplied = Math.max(mLastApplied, mCommitIndex);
                           
			    // System.out.println("before isEmpty");
			    //Boolean test=true;
			    //if(localCache!=null){
			    //test=localCache.isEmpty();
			    // }
                            //System.out.println("After isEmpty");
			    //System.out.println("///////////localcache null"+test);
		    			    
			    if ((localCache.isEmpty() == false))// && (lastLeader != -1))
				{
				    // assert(!localCache.isEmpty());			    
                                    Entry[] content= new Entry[localCache.size()];
				    localCache.toArray(content);
				    //RaftResponses.setTerm(term);
				    //RaftResponses.clearAppendResponses(term);
				    remoteAppendEntries(lastLeader, term, lastLeader, 0,0, content, 0);
				    //check response
				    //if (RaftResponses.getAppendResponses(term)[lastLeader] == 0)  
				    //successful append, otherwise send next time
				    //  {
				    localCache.clear();
				    //   RaftResponses.setAppendResponse(lastLeader, -1, term);
				    // }
				}
			    
			    followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
			    return 0;
        		}
        		else  // append entries
			    {
        			//System.out.println("Follower "+mID+"in true append");
        			if (prevLogIndex == -1)  // append from start
        			{
        				mLog.insert(entries, -1, prevLogTerm);
        				result =0;
        				if (leaderCommit>mCommitIndex)
        				{
    				      mCommitIndex = Math.min(leaderCommit, mLog.getLastIndex());
    				      mLastApplied = Math.max(mLastApplied, mCommitIndex);
        				}
        			}
				else  //append from somewhere, check
				{
        	        	Entry testEntry = mLog.getEntry(prevLogIndex);
        	        	if (testEntry != null && testEntry.term == prevLogTerm) //same index,
        	        	//same term, should append
        	        	{
        	        		mLog.insert(entries, prevLogIndex, prevLogTerm);
        	        		result = 0;
        	        		if (leaderCommit>mCommitIndex)
      					  	{
        	        			mCommitIndex = Math.min(leaderCommit,
        	        			mLog.getLastIndex());
      					      	mLastApplied = Math.max(mLastApplied,
      					      	mCommitIndex);
      					  	}
        	        		//System.out.println("insert successful");
        	        	}
        	        	else  //wrong entry, does not append
        	        	{
        	        		mLastApplied = Math.max(mLastApplied, mCommitIndex);
        	        	}
      			  }
        		  followerTimer = this.scheduleTimer(ELECTION_TIMEOUT,mID);
        		  //System.out.println("server "+mID+" return "+result);
        		  return result; 
			}
	           }//end append from server
    	   }//end synchronize
}//end append
    
    // @param id of the timer that timed out
  public void handleTimeout (int timerID) 
  {
    synchronized (mLock) 
    {
      if (timerID == mID)
      {
        followerTimer.cancel();
        System.out.println("server "+mID+" in follower handletimeout and send remote request");
        //ready to switch to candidate if i have all commited index entry
        RaftMode mode = new CandidateMode();
        RaftServerImpl.setMode (mode);
      }
      else
      {/*
        cacheTimer.cancel();  //check cache
        int term = mConfig.getCurrentTerm();
	try{
        if ((localCache.isEmpty() == false) && (lastLeader != -1))   
     {
          Entry[] content= new Entry[localCache.size()];
          localCache.toArray(content);
          this.remoteAppendEntries(lastLeader, 0, lastLeader, 0,0, content, 0);
	  //check response
          if (RaftResponses.getAppendResponses(term)[lastLeader] == 0)  
          //successful append, otherwise send next time
          {
        	  localCache.clear();
              RaftResponses.setAppendResponse(lastLeader, -1, term);
	      }
        }
	}
	catch(Exception e){
	    System.out.println("localcache null pointer");
	}
        cacheTimer = this.scheduleTimer(10, mID+1);
       */}
    }
  }//end handle timeout
}
