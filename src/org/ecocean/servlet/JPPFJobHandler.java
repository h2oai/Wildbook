package org.ecocean.servlet;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Vector;

import javax.jdo.FetchPlan;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.ecocean.*;
import org.ecocean.grid.*;

import java.util.concurrent.ThreadPoolExecutor;

import org.jppf.server.protocol.JPPFTask;
import org.jppf.client.*;


//handles operations to sharks. possible operations include, create new, add encounter, remove encounter from
public class JPPFJobHandler extends HttpServlet {

	
	public void init(ServletConfig config) throws ServletException {
    	super.init(config);
  	}
	
	private int combinations(int n, int k) {
		int t= 1;
		for (int i= Math.min(k, n-k), l= 1; i >= 1; i--, n--, l++) {
			t*= n; t/= l;
		}
		return t;
	}
		
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException{
		doPost(request, response);
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException{
		Shepherd myShepherd=new Shepherd();
		GridManager gm=GridManagerFactory.getGridManager();
		//set up for response
		response.setContentType("text/html");
		PrintWriter out = response.getWriter();
		String action=request.getParameter("action");
		System.out.println("JPPFJobHandler action is: "+action);
		if(action!=null){
			

			
			if ((action.equals("removeTask"))&&(request.getParameter("taskID")!=null)) {
				myShepherd.beginDBTransaction();
				boolean locked=false;
				//gotta check if it's a valid scanTask to begin with!
				
				//check for permissions to delete this scanTask
				boolean deletePermission=false;
				if(request.isUserInRole("researcher")){deletePermission=true;}

				if((myShepherd.isScanTask(request.getParameter("taskID")))&&(deletePermission)){
					try {
						
						//change
						ThreadPoolExecutor es=SharkGridThreadExecutorService.getExecutorService();

						ScanTask st=myShepherd.getScanTask(request.getParameter("taskID"));
						myShepherd.getPM().deletePersistent(st);

						myShepherd.commitDBTransaction();
						
						//scanTaskCleanupThread swiThread=new scanTaskCleanupThread(request.getParameter("taskID"));
						es.execute(new JPPFJobCleanupThread(request.getParameter("taskID")));
						
					}
					catch(Exception e) {
						locked=true;
						System.out.println("I encounter the following error while deleting a JPPF Job:");
						e.printStackTrace();
						
						myShepherd.rollbackDBTransaction();
					}
					if(!locked) {
						//confirm success
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Success:</strong> The JPPF grid job <i>"+request.getParameter("taskID")+"</i> has been removed.");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Return to the grid administration page.</a></p>\n");
						out.println(ServletUtilities.getFooter());
					} 
					else{
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Error:</strong> The JPPF job <i>"+request.getParameter("taskID")+"</i> was not removed. The job may be locked by another user or in process. Check the logs for more information.");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Return to the grid administration page.</a></p>\n");
						out.println(ServletUtilities.getFooter());
						
					}
				}
				else {
					myShepherd.rollbackDBTransaction();
					out.println(ServletUtilities.getHeader());
					out.println("<strong>Error:</strong> The job <i>"+request.getParameter("taskID")+"</i> was not identified in the database.");
					out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Return to grid administration page.</a></p>\n");
					out.println(ServletUtilities.getFooter());
				}
			}
			
			else if ((action.equals("addTask"))&&(request.isUserInRole("researcher"))&&(request.getParameter("encounterNumber")!=null)) {
				
				myShepherd.getPM().setIgnoreCache(true);
				
				boolean locked=false;
				boolean successfulStore=false;
				
				//set up our properties
				java.util.Properties props2=new java.util.Properties();
				String secondRun="true";
				String rightScan="false";
				boolean isRightScan=false;
				boolean writeThis=true;
				//String uniqueNum="";
				if(request.getParameter("writeThis")==null) {
					writeThis=false;
				}
				if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
					rightScan="true";
					isRightScan=true;
				}
				props2.setProperty("epsilon", "0.01");
				props2.setProperty("R", "8");
				props2.setProperty("Sizelim", "0.85");
				props2.setProperty("maxTriangleRotation", "10");
				props2.setProperty("C", "0.99");
				props2.setProperty("secondRun", secondRun);
				props2.setProperty("rightScan", rightScan);
				
				//let's check if a scanTask for this exists
				System.out.println("JPPFJobHandler: Checking whether this is a new job...");
				
				myShepherd.beginDBTransaction();
				
				
				String sideIdentifier="L";
				
				if(rightScan.equals("true")){
					sideIdentifier="R";
					//numComparisons=myShepherd.getNumEncountersWithSpotData(true);
				}
				else{
					//numComparisons=myShepherd.getNumEncountersWithSpotData(false);
				}
				String taskIdentifier="scan"+sideIdentifier+request.getParameter("encounterNumber");
				ScanTask st=new ScanTask();
				
				//let's do a check to see if too many scanTasks are in the queue
				int taskLimit=gm.getScanTaskLimit();
				int currentNumScanTasks=myShepherd.getNumUnfinishedScanTasks();
				myShepherd.getPM().getFetchPlan().setGroup(FetchPlan.DEFAULT);
				System.out.println("currentNumScanTasks is: "+currentNumScanTasks);
				//int currentNumScanTasks=0;
				if(currentNumScanTasks<taskLimit){
					
					int numComparisons=0;
					if(rightScan.equals("true")){
						//sideIdentifier="R";
						numComparisons=myShepherd.getNumEncountersWithSpotData(true);
					}
					else{
						numComparisons=myShepherd.getNumEncountersWithSpotData(false);
					}
					myShepherd.getPM().getFetchPlan().setGroup(FetchPlan.DEFAULT);
					
					System.out.println("scanTaskHandler: Under the limit, so proceeding to check for condiions for creating a new scanTask...");
					if((!myShepherd.isScanTask(taskIdentifier))){
						System.out.println("scanTaskHandler: This scanTask does not exist, so go create it...");
					
						//check if this encounter has the needed spots to create the task
						boolean hasNeededSpots=false;
						Encounter enc=myShepherd.getEncounter(request.getParameter("encounterNumber"));
						if((rightScan.equals("true"))&&(enc.getRightSpots()!=null)){hasNeededSpots=true;}
						else if(enc.getSpots()!=null){hasNeededSpots=true;}
					
						if(hasNeededSpots) {
							
							System.out.println("scanTaskHandler: I have needed spots...proceeding...");
							
							
							st=new ScanTask(myShepherd, taskIdentifier, props2, request.getParameter("encounterNumber"), writeThis);
							st.setNumComparisons(numComparisons-1);
							if(request.getRemoteUser()!=null){st.setSubmitter(request.getRemoteUser());}
							System.out.println("scanTaskHandler: About to create a scanTask...");
							successfulStore=myShepherd.storeNewTask(st);
							if(!successfulStore){
								
								System.out.println("scanTaskHandler: Unsuccessful store...");
								
								myShepherd.rollbackDBTransaction();
								myShepherd.closeDBTransaction();
								locked=true;
							}
							else{
								System.out.println("scanTaskHandler: Successful store...");
								
								myShepherd.commitDBTransaction();
								myShepherd.closeDBTransaction();
								myShepherd=new Shepherd();
							}
						}
						else {
							myShepherd.rollbackDBTransaction();
							myShepherd.closeDBTransaction();
							locked=true;
						}
					
					} else if(myShepherd.isScanTask(taskIdentifier)) {
						
						System.out.println("JPPFJobHandler: This is an existing job...");
						
						
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					
						String rightFilter="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="&rightSide=true";
						}
					
						//if it exists already, advance to the scanTask administration page to await its completion
						response.sendRedirect("http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp");
					}
					else{
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
				}
				
				if(!locked&&successfulStore) {

					try{
						
						
						ThreadPoolExecutor es=SharkGridThreadExecutorService.getExecutorService();
						
						//create the job
						JPPFJob job = new JPPFJob();
						 
					    // give this job a readable unique id that we can use to
					    // monitor and manage it.
					    job.setId("Template Job Id");

						//pass the job into the creation thread
						es.execute(new ScanWorkItemCreationThread(taskIdentifier, isRightScan, request.getParameter("encounterNumber"), writeThis));
						

					} 
					catch(Exception e) {
						System.out.println("I failed while constructing the workItems for a new scanTask.");
						e.printStackTrace();
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
					if(!locked){
						System.out.println("Trying to commit the add of the scanWorkItems");
						

						
						//myShepherd.commitDBTransaction();
						//myShepherd.closeDBTransaction();
						System.out.println("I committed the workItems!");
				
						String rightFilter="L";
						String rightURL="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="R";
							rightURL="&rightSide=true";
							
						}
						
						//confirm success
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Success:</strong> Your scan was successfully added to the sharkGrid!");
						//out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/workAppletScan.jsp?number=scan"+rightFilter+request.getParameter("encounterNumber")+rightURL+"\">Start scanning for a match.</a></p>\n");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/encounter.jsp?number="+request.getParameter("encounterNumber")+"\">Return to encounter "+request.getParameter("encounterNumber")+".</a></p>\n");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp"+"\">Go to sharkGrid administration to monitor for completion.</a></p>\n");
						out.println(ServletUtilities.getFooter());
					}
					else{
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Failure:</strong> The scan could not be created or was not fully created!");
						if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
						out.println(ServletUtilities.getFooter());

					}
				}
				else {
							out.println(ServletUtilities.getHeader());
							out.println("<strong>Failure:</strong> I have NOT added this scanTask to the queue.");
							if(currentNumScanTasks<taskLimit){
								out.println("The unfinished task limit of "+taskLimit+" has been filled. Please try adding the task to the queue again after existing tasks have finished.");
							}
							if(request.isUserInRole("researcher")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
							out.println(ServletUtilities.getFooter());
				}
			}
			
			
			else if ((action.equals("addTuningTask"))&&(request.isUserInRole("admin"))) {
				
				//myShepherd.getPM().setIgnoreCache(true);
				
				boolean locked=false;
				//String readableName="";
				boolean successfulStore=false;
				
				int maxNumWorkItems = 99999999;
				if((request.getParameter("maxNumWorkItems")!=null)&&(!request.getParameter("maxNumWorkItems").equals(""))){
					maxNumWorkItems = Integer.parseInt(request.getParameter("maxNumWorkItems"));
				}
				
				//set up our properties
				java.util.Properties props2=new java.util.Properties();
				String secondRun="true";
				String rightScan="false";
				boolean isRightScan=false;
				boolean writeThis=true;
				//String uniqueNum="";
				if(request.getParameter("writeThis")==null) {
					writeThis=false;
				}
				if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
					rightScan="true";
					isRightScan=true;
				}
				props2.setProperty("epsilon", "0.01");
				props2.setProperty("R", "8");
				props2.setProperty("Sizelim", "0.85");
				props2.setProperty("maxTriangleRotation", "10");
				props2.setProperty("C", "0.99");
				props2.setProperty("secondRun", secondRun);
				props2.setProperty("rightScan", rightScan);
				
				//let's check if a scanTask for this exists
				System.out.println("scanTaskHandler: Checking whether this is a new scanTask...");
				
				myShepherd.beginDBTransaction();
				
				
				String sideIdentifier="L";
				
				if(rightScan.equals("true")){
					sideIdentifier="R";
					//numComparisons=myShepherd.getNumEncountersWithSpotData(true);
				}
				else{
					//numComparisons=myShepherd.getNumEncountersWithSpotData(false);
				}
				String taskIdentifier="TuningTask";
				ScanTask st=new ScanTask();
				
				//let's do a check to see if too many scanTasks are in the queue
				int taskLimit=gm.getScanTaskLimit();
				int currentNumScanTasks=myShepherd.getNumUnfinishedScanTasks();
				myShepherd.getPM().getFetchPlan().setGroup(FetchPlan.DEFAULT);
				System.out.println("currentNumScanTasks is: "+currentNumScanTasks);
				//int currentNumScanTasks=0;
				if(currentNumScanTasks<taskLimit){
					
					Vector leftSharks=myShepherd.getPossibleTrainingIndividuals();
					Vector rightSharks=myShepherd.getRightPossibleTrainingIndividuals();
					
					int numComparisons=0;
					
					//calculate the number of comparisons that can be made
					int numLeftSharks=leftSharks.size();
					for(int i=0;i<numLeftSharks;i++){
						MarkedIndividual s=(MarkedIndividual)leftSharks.get(i);
						int numTrainable=s.getNumberTrainableEncounters();
						//int numCompareEncounters=s.getNumberTrainableEncounters();
						//for(int j=(numCompareEncounters-1);j>1;j--){
						//	numCompareEncounters=numCompareEncounters*j;
						//}
						//numCompareEncounters=numCompareEncounters/(2*(numTrainable-2));
						//numComparisons=numComparisons+numCompareEncounters;
						numComparisons=numComparisons+combinations(numTrainable,2);

					}
					int numRightSharks=rightSharks.size();
					for(int i=0;i<numRightSharks;i++){
						MarkedIndividual s=(MarkedIndividual)rightSharks.get(i);
						int numCompareEncounters=s.getNumberRightTrainableEncounters();
						//for(int j=(numCompareEncounters-1);j>1;j--){
							//numCompareEncounters=numCompareEncounters*j;
						//}
						//numComparisons=numComparisons+numCompareEncounters;
						numComparisons=numComparisons+combinations(numCompareEncounters,2);
					}
					
					

					System.out.println("scanTaskHandler: Under the limit, so proceeding to check for condiions for creating a new scanTask...");
					if((!myShepherd.isScanTask(taskIdentifier))){
						//System.out.println("scanTaskHandler: This scanTask does not exist, so go create it...");
					

						st=new ScanTask(myShepherd, taskIdentifier, props2, "TuningTask", writeThis);
						
						if(numComparisons<(2*maxNumWorkItems)){
							st.setNumComparisons(numComparisons);
						}
						else{
							st.setNumComparisons((2*maxNumWorkItems));
						}
						
						
						if(request.getRemoteUser()!=null){st.setSubmitter(request.getRemoteUser());}
						System.out.println("scanTaskHandler: About to create a TuningTask...");
						successfulStore=myShepherd.storeNewTask(st);
						if(!successfulStore){
								
							System.out.println("scanTaskHandler: Unsuccessful TuningTask store...");
								
							myShepherd.rollbackDBTransaction();
							myShepherd.closeDBTransaction();
							locked=true;
						}
						else{
							System.out.println("scanTaskHandler: Successful TuningTask store...");
								
							myShepherd.commitDBTransaction();
							myShepherd.closeDBTransaction();
							myShepherd=new Shepherd();
							
							
						}

					
					} else if(myShepherd.isScanTask(taskIdentifier)) {
						
						System.out.println("scanTaskHandler: This is an existing scanTask...");
						
						
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					
						String rightFilter="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="&rightSide=true";
						}
					
						//if it exists already, advance to the scan page to assist it
						response.sendRedirect("http://"+CommonConfiguration.getURLLocation()+"/encounters/workAppletScan.jsp?writeThis=true&number="+taskIdentifier+rightFilter);
					}
					else{
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
				}
				
				if(!locked&&successfulStore) {

					try{
						
						
						ThreadPoolExecutor es=SharkGridThreadExecutorService.getExecutorService();
						es.execute(new TuningTaskCreationThread(taskIdentifier, writeThis, maxNumWorkItems));
						

					} 
					catch(Exception e) {
						System.out.println("I failed while constructing the workItems for a new scanTask.");
						e.printStackTrace();
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
					if(!locked){
						System.out.println("Trying to commit the add of the scanWorkItems");
						

						
						//myShepherd.commitDBTransaction();
						//myShepherd.closeDBTransaction();
						System.out.println("I committed the workItems!");
				
						String rightFilter="L";
						String rightURL="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="R";
							rightURL="&rightSide=true";
							
						}
						
						//confirm success
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Success:</strong> Your scan was successfully added to the sharkGrid!");
						//out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/workAppletScan.jsp?number=scan"+rightFilter+request.getParameter("encounterNumber")+rightURL+"\">Start scanning for a match.</a></p>\n");
						//out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/encounter.jsp?number="+request.getParameter("encounterNumber")+"\">Return to encounter "+request.getParameter("encounterNumber")+".</a></p>\n");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp"+"\">Return to sharkGrid administration.</a></p>\n");
						out.println(ServletUtilities.getFooter());
					}
					else{
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Failure:</strong> The scan could not be created or was not fully created!");
						if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
						out.println(ServletUtilities.getFooter());

					}
				}
				else {
							out.println(ServletUtilities.getHeader());
							out.println("<strong>Failure:</strong> I have NOT added this scanTask to the queue.");
							if(currentNumScanTasks<taskLimit){
								out.println("The unfinished task limit of "+taskLimit+" has been filled. Please try adding the task to the queue again after existing tasks have finished.");
							}
							if(request.isUserInRole("researcher")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
							out.println(ServletUtilities.getFooter());
				}
			}
			
			

			else if ((action.equals("addFalseMatchTask"))&&(request.isUserInRole("admin"))) {

				boolean locked=false;
				boolean successfulStore=false;
				
				int maxNumWorkItems = 99999999;
				if((request.getParameter("maxNumWorkItems")!=null)&&(!request.getParameter("maxNumWorkItems").equals(""))){
					maxNumWorkItems = Integer.parseInt(request.getParameter("maxNumWorkItems"));
				}
				
				//set up our properties
				java.util.Properties props2=new java.util.Properties();
				String secondRun="true";
				String rightScan="false";
				boolean isRightScan=false;
				boolean writeThis=true;
				//String uniqueNum="";
				if(request.getParameter("writeThis")==null) {
					writeThis=false;
				}
				if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
					rightScan="true";
					isRightScan=true;
				}
				props2.setProperty("epsilon", "0.01");
				props2.setProperty("R", "8");
				props2.setProperty("Sizelim", "0.85");
				props2.setProperty("maxTriangleRotation", "10");
				props2.setProperty("C", "0.99");
				props2.setProperty("secondRun", secondRun);
				props2.setProperty("rightScan", rightScan);
				
				//let's check if a scanTask for this exists
				System.out.println("scanTaskHandler: Checking whether this is a new False Match scanTask...");
				
				myShepherd.beginDBTransaction();
				
				
				String sideIdentifier="L";
				
				if(rightScan.equals("true")){
					sideIdentifier="R";
				}

				String taskIdentifier="FalseMatchTask";
				ScanTask st=new ScanTask();
				
				//let's do a check to see if too many scanTasks are in the queue
				int taskLimit=gm.getScanTaskLimit();
				int currentNumScanTasks=myShepherd.getNumUnfinishedScanTasks();
				myShepherd.getPM().getFetchPlan().setGroup(FetchPlan.DEFAULT);
				System.out.println("currentNumScanTasks is: "+currentNumScanTasks);
				//int currentNumScanTasks=0;
				if(currentNumScanTasks<taskLimit){
					
					//Vector leftSharks=myShepherd.getPossibleTrainingSharks();
					//Vector rightSharks=myShepherd.getRightPossibleTrainingSharks();
					
					int numComparisons=maxNumWorkItems*2;
					
					

					System.out.println("scanTaskHandler: Under the limit, so proceeding to check for condiions for creating a new scanTask...");
					if((!myShepherd.isScanTask(taskIdentifier))){
						//System.out.println("scanTaskHandler: This scanTask does not exist, so go create it...");
					

						st=new ScanTask(myShepherd, taskIdentifier, props2, "FalseMatchTask", writeThis);
						st.setNumComparisons(numComparisons);
						if(request.getRemoteUser()!=null){st.setSubmitter(request.getRemoteUser());}
						System.out.println("scanTaskHandler: About to create a TuningTask...");
						successfulStore=myShepherd.storeNewTask(st);
						if(!successfulStore){
								
							System.out.println("scanTaskHandler: Unsuccessful FalseMatchTask store...");
								
							myShepherd.rollbackDBTransaction();
							myShepherd.closeDBTransaction();
							locked=true;
						}
						else{
							System.out.println("scanTaskHandler: Successful FalseMatchTask store...");
								
							myShepherd.commitDBTransaction();
							myShepherd.closeDBTransaction();
							myShepherd=new Shepherd();
							
							
						}

					
					} else if(myShepherd.isScanTask(taskIdentifier)) {
						
						System.out.println("scanTaskHandler: This is an existing scanTask...");
						
						
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					
						String rightFilter="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="&rightSide=true";
						}
					
						//if it exists already, advance to the scan page to assist it
						response.sendRedirect("http://"+CommonConfiguration.getURLLocation()+"/encounters/workAppletScan.jsp?writeThis=true&number="+taskIdentifier+rightFilter);
					}
					else{
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
				}
				
				if(!locked&&successfulStore) {

					try{
						
						
						ThreadPoolExecutor es=SharkGridThreadExecutorService.getExecutorService();
						es.execute(new FalseMatchCreationThread(maxNumWorkItems,taskIdentifier));
						

					} 
					catch(Exception e) {
						System.out.println("I failed while constructing the workItems for a new scanTask.");
						e.printStackTrace();
						myShepherd.rollbackDBTransaction();
						myShepherd.closeDBTransaction();
						locked=true;
					}
					if(!locked){
						System.out.println("Trying to commit the add of the scanWorkItems");
						

						
						//myShepherd.commitDBTransaction();
						//myShepherd.closeDBTransaction();
						System.out.println("I committed the workItems!");
				
						String rightFilter="L";
						String rightURL="";
						if((request.getParameter("rightSide")!=null)&&(request.getParameter("rightSide").equals("true"))) {
							rightFilter="R";
							rightURL="&rightSide=true";
							
						}
						
						//confirm success
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Success:</strong> Your scan was successfully added to the sharkGrid!");
						//out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/workAppletScan.jsp?number=scan"+rightFilter+request.getParameter("encounterNumber")+rightURL+"\">Start scanning for a match.</a></p>\n");
						//out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/encounters/encounter.jsp?number="+request.getParameter("encounterNumber")+"\">Return to encounter "+request.getParameter("encounterNumber")+".</a></p>\n");
						out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp"+"\">Return to sharkGrid administration.</a></p>\n");
						out.println(ServletUtilities.getFooter());
					}
					else{
						out.println(ServletUtilities.getHeader());
						out.println("<strong>Failure:</strong> The scan could not be created or was not fully created!");
						if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
						out.println(ServletUtilities.getFooter());

					}
				}
				else {
							out.println(ServletUtilities.getHeader());
							out.println("<strong>Failure:</strong> I have NOT added this scanTask to the queue.");
							if(currentNumScanTasks<taskLimit){
								out.println("The unfinished task limit of "+taskLimit+" has been filled. Please try adding the task to the queue again after existing tasks have finished.");
							}
							if(request.isUserInRole("researcher")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
							out.println(ServletUtilities.getFooter());
				}
			}
			
			
			//delete all scan-related items
			else if ((action.equals("removeAllWorkItems"))&&(request.isUserInRole("admin"))) {
				try{
					
					GridCleanupThread swiThread=new GridCleanupThread();
					gm.removeAllWorkItems();
				
					//confirm success
					out.println(ServletUtilities.getHeader());
					out.println("<strong>Success:</strong> I removed all outstanding scanWorkItems from the database.<br>/<strong>Warning!</strong> <em>This may cause any outstanding scanTasks to fail!</em>");
					if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
					out.println(ServletUtilities.getFooter());
				}
				catch(Exception e){
					e.printStackTrace();
					myShepherd.rollbackDBTransaction();
					out.println(ServletUtilities.getHeader());
					out.println("<strong>Failure:</strong> I failed to remove all outstanding scanWorkItems from the database. Check the log for more information.");
					if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
					out.println(ServletUtilities.getFooter());
					
				}
			}
			
			
			else {
				
					out.println(ServletUtilities.getHeader());
					out.println("<p>I did not receive enough data to process your command, or you do not have the necessary permissions to perform this operation.</p>");
					if(request.isUserInRole("admin")) {out.println("<p><a href=\"http://"+CommonConfiguration.getURLLocation()+"/appadmin/scanTaskAdmin.jsp\">Go to sharkGrid administration.</a></p>\n");}
					out.println(ServletUtilities.getFooter());
			}
			
			
		}
		else {
			out.println(ServletUtilities.getHeader());
			out.println("<p>I did not receive enough data to process your command, or you do not have the necessary permissions to perform this operation. </p>");
			out.println("<p>Please try again or <a href=\"welcome.jsp\">login here</a>.");
			out.println(ServletUtilities.getFooter());
			//npe2.printStackTrace();
		}
			myShepherd.closeDBTransaction();
			myShepherd=null;
			out.flush();
			out.close();
		}
	
}