package org.ecocean.servlet.importer;

import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.net.*;

import org.ecocean.grid.*;

import java.io.*;
import java.util.*;
import java.io.FileInputStream;
import java.io.File;
import java.io.FileNotFoundException;

import javax.jdo.*;

import java.lang.StringBuffer;
import java.util.Vector;
import java.util.Iterator;
import java.lang.NumberFormatException;

import org.ecocean.*;
import org.ecocean.servlet.*;
import org.ecocean.media.*;
import org.ecocean.genetics.*;
import org.ecocean.tag.SatelliteTag;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.hssf.usermodel.*;
import org.apache.poi.poifs.filesystem.NPOIFSFileSystem;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class StandardImport extends HttpServlet {

	// variables shared by any single import instance
	Shepherd myShepherd;
	Map<String,Integer> colIndexMap = new HashMap<String, Integer>();
	Set<String> unusedColumns;
	Set<String> missingColumns; // columns we look for but don't find
	List<String> missingPhotos = new ArrayList<String>();
	List<String> foundPhotos = new ArrayList<String>();
	int numFolderRows = 0;
	boolean committing = false;
        boolean generateChildrenAssets = false;
	PrintWriter out;
	// verbose variable can be switched on/off throughout the import for debugging
	boolean verbose = false;
	String photoDirectory;

	private AssetStore astore;

	// just for lazy loading a var used on each row
	Integer numMediaAssets;

        Map<String,MediaAsset> myAssets = new HashMap<String,MediaAsset>();

  public void init(ServletConfig config) throws ServletException {
    super.init(config);
  }

  public void doGet(HttpServletRequest request,  HttpServletResponse response) throws ServletException,  IOException {
    doPost(request,  response);
  }

  public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException,  IOException {
    if (request.getCharacterEncoding() == null) {
      request.setCharacterEncoding("utf-8");
    }
    response.setContentType("text/html; charset=UTF-8");
    String context = ServletUtilities.getContext(request);
    myAssets = new HashMap<String,MediaAsset>();  //zero this out from previous (e.g. uncommited)
    myShepherd = new Shepherd(context);
    out = response.getWriter();
    astore = getAssetStore(myShepherd);

    User creator = AccessControl.getUser(request, myShepherd);
    ImportTask itask = new ImportTask(creator);
    itask.setPassedParameters(request);

    if(astore!=null){
      System.out.println("astore is OK!");
    }
    else{
      System.out.println("astore is null...BOO!!");
      out.println("<p>I could not find a default AssetStore. Please create one.</p>");
      return;
    }

    String importDir = CommonConfiguration.getImportDir(context);
    if (importDir == null) throw new RuntimeException("You MUST have 'importDir' set in commonConfigurations to use importer");
    System.out.println("importDir is: "+importDir);
    
    //filename *can* contain a subdir, such as "subdir/foo.xls"
    String filename = Util.safePath(request.getParameter("filename"));  //this will be relative to importDir now!
    if (filename == null) {  //note: safePath() might return null if it found evil stuff in filename=
      System.out.println("Filename request parameter was not set in the URL.");
      out.println("<p>I could not find a filename parameter in the URL. Please specify the full path to the Excel import file as the ?filename= parameter on the URL.</p>");
      return;
    }
    File dataFile = new File(importDir + "/" + filename);
    if(!dataFile.exists()){
      out.println("<p>I found a filename parameter in the URL, but I couldn't find the file itself at the path your specified: "+filename+"</p>");
      return;
    }
    
    //String uploadDir = importFile.getParentFile().getAbsolutePath();
    //if (subdir != null) uploadDir = subdir;
    //System.out.println("uploadDir is: "+uploadDir);
    photoDirectory = dataFile.getParentFile().getAbsolutePath();
    System.out.println("photoDirectory is: "+photoDirectory);

    
    //File dataFile = new File(uploadDir + "/" + filename);
    //boolean dataFound = dataFile.exists();

    String rootDir = getServletContext().getRealPath("/");
    File xlsFile = importXlsFile(rootDir);
    if (xlsFile == null) {
        out.println("<h2 class=\"error\">Could not determine latest Wildbook Standard Format XLS file!</h2>");
    } else {
        DateTime dt = new DateTime(xlsFile.lastModified());
        out.println("<p><b>The latest <b>Wildbook Standard Format XLS</b> template is called \"<a href=\"import/" + xlsFile.getName() + "\">" + xlsFile.getName() + "</a>\" (updated " + dt.toString().substring(0,10) + ").</b><br /><i>Please make sure you are using the current version for best results.</p>");
    }

    missingColumns = new HashSet<String>();
    missingPhotos = new ArrayList<String>();
		foundPhotos = new ArrayList<String>();
		numFolderRows = 0;

    committing = Util.requestParameterSet(request.getParameter("commit"));
    generateChildrenAssets = Util.requestParameterSet(request.getParameter("generateChildrenAssets"));

    out.println("<h2>File Overview: </h2>");
    out.println("<ul>");
    out.println("<li>Directory: "+photoDirectory+"</li>");
    out.println("<li>Filename: "+filename+"</li>");
    //out.println("<li>File found = "+dataFound+"</li>");

    Workbook wb = null;
    try {
      wb = WorkbookFactory.create(dataFile);
    } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException invalidFormat) {
      out.println("<err>InvalidFormatException on input file "+filename+". Only excel files supported.</err>");
      return;
    } catch (Exception ex) {  //pokemon!
      out.println("<err><b>" + filename + "</b> got error: <i>" + ex.toString() + "</i></err>");
      System.out.println("=== if you see this exception, it may be something wrong with permissions ===");
      ex.printStackTrace();
      return;
    }
    int sheetNum = 0;
    try {
        sheetNum = Integer.parseInt(request.getParameter("sheetNumber"));
        if (sheetNum < 0) sheetNum = 0;
    } catch (NumberFormatException ex) {}
    int numSheets = wb.getNumberOfSheets();
    if (sheetNum >= numSheets) sheetNum = 0;

    Sheet sheet = wb.getSheetAt(sheetNum);
    int physicalNumberOfRows = sheet.getPhysicalNumberOfRows();

    List<Integer> skipRows = new ArrayList<Integer>();
    String[] skipRowParam = request.getParameterValues("skipRow");
    String skipDisplay = "";
    if ((skipRowParam != null) && (skipRowParam.length > 0)) {
        for (int i = 0 ; i < skipRowParam.length ; i++) {
            try {
                int p = Integer.parseInt(skipRowParam[i]);
                if ((p >= 0) && (p < physicalNumberOfRows)) {
                    skipRows.add(p);
                    skipDisplay += p + " ";
                }
            } catch (NumberFormatException ex) {}
        }
    }

    int printPeriod = 100;
    try {
        int pp = Integer.parseInt(request.getParameter("printPeriod"));
        if (pp > 0) printPeriod = pp;
    } catch (NumberFormatException ex) {}

    if (committing) {
        out.println("<li>ImportTask id = <b><a href=\"imports.jsp?taskId=" + itask.getId() + "\">" + itask.getId() + "</a></b></li>");
    } else {
        out.println("<li>ImportTask id = <b>" + itask.getId() + "</b></li>");
    }
    out.println("<li>Sheet number = " + sheetNum + " (of " + numSheets + ") <i>\"" + sheet.getSheetName() + "\"</i></li>");
    out.println("<li>Num Rows = "+physicalNumberOfRows+" <i>(echo every " + printPeriod + " rows)</i></li>");
    if (skipRows.size() > 0) out.println("<li>Skipping rows: " + skipDisplay + "</li>");
    int rows = sheet.getPhysicalNumberOfRows();; // No of rows
    Row firstRow = sheet.getRow(0);
    // below line is important for on-screen logging
    initColIndexVariables(firstRow);
    int cols = firstRow.getPhysicalNumberOfCells(); // No of columns
    int lastColNum = firstRow.getLastCellNum();

    out.println("<li>Num Cols = "+cols+"</li>");
    out.println("<li>Last col num = "+lastColNum+"</li>");
    out.println("<li>generateChildrenAssets? = " + generateChildrenAssets + "</li>");
    out.println("<li><em>committing = "+committing+"</em></li>");
    out.println("</ul>");
    out.println("<h2>Column headings:</h2><ul>");
    out.println("<li>number columns = "+colIndexMap.size()+"</li>");
    for (String heading: colIndexMap.keySet()) out.println("<li>"+colIndexMap.get(heading)+": "+heading+"</li>");
    out.println("</ul>");

    LocalDateTime ldt = new LocalDateTime();
    String importComment = "<p style=\"import-comment\">import <i>" + itask.getId() + "</i> at " + ldt.toString() + "</p>";
    System.out.println("===== ImportTask id=" + itask.getId() + " (committing=" + committing + ")");
    if (committing) myShepherd.beginDBTransaction();
    out.println("<h2>Beginning row loop:</h2>"); 
    out.println("<ul>");
    // one encounter per-row. We keep these running.
    Occurrence occ = null;
    List<Encounter> encsCreated = new ArrayList<Encounter>();
    int maxRows = 50000;
    int offset = 0;
    for (int i=1+offset; i<rows&&i<(maxRows+offset); i++) {
        if (skipRows.contains(i)) {
            System.out.println("INFO: skipping row " + i + " due to skipRow arg");
            continue;
        }

    	MarkedIndividual mark = null;
    	verbose = ((i%printPeriod)==0);
      try {

        if (committing) myShepherd.beginDBTransaction();
        Row row = sheet.getRow(i);
        if (isRowEmpty(row)) continue;

        // here's the central logic
        ArrayList<Annotation> annotations = loadAnnotations(row);
        Encounter enc = loadEncounter(row, annotations, context);
        enc.addComments(importComment);
        occ = loadOccurrence(row, occ, enc);
        occ.addComments(importComment);
        mark = loadIndividual(row, enc);
        encsCreated.add(enc);

        if (committing) {

          for (Annotation ann: annotations) {
            try {
              MediaAsset ma = ann.getMediaAsset();
              if (ma!=null) {
                myShepherd.storeNewAnnotation(ann);
                if (generateChildrenAssets) {
                    ArrayList<MediaAsset> kids = ma.findChildren(myShepherd);
                    if ((kids == null) || (kids.size() < 1)) {
                        ma.setMetadata();
                        ma.updateStandardChildren(myShepherd);
                    }
                }
              }
            }
            catch (Exception e) {
              System.out.println("EXCEPTION on annot/ma persisting!");
              e.printStackTrace();
            }
          }

          myShepherd.storeNewEncounter(enc, enc.getCatalogNumber());
        	if (!myShepherd.isOccurrence(occ))        myShepherd.storeNewOccurrence(occ);
        	if (!myShepherd.isMarkedIndividual(mark)) myShepherd.storeNewMarkedIndividual(mark);
        	myShepherd.commitDBTransaction();
        }

        if (verbose) {
          out.println("<li>Parsed row ("+i+")<ul>"
          +"<li> Enc "+getEncounterDisplayString(enc)+"</li>"
          +"<li> individual "+mark+"</li>"
          +"<li> occurrence "+occ+"</li>"
          +"<li> dateInMillis "+enc.getDateInMilliseconds()+"</li>"
          +"<li> sex "+enc.getSex()+"</li>"
          +"<li> lifeStage "+enc.getLifeStage()+"</li>"
          +"</ul></li>");

        }
        
      }
      catch (Exception e) {
        out.println("Encountered an error while importing the file.");
        e.printStackTrace(out);
        myShepherd.rollbackDBTransaction();
      }
    }
    out.println("</ul>");


    out.println("<h2><em>UNUSED</em> Column headings ("+unusedColumns.size()+"):</h2><ul>");
    for (String heading: unusedColumns) {
    	out.println("<li>"+heading+"</li>");
    }
    out.println("</ul>");


    if (committing) {
        itask.setEncounters(encsCreated);
        myShepherd.getPM().makePersistent(itask);
    }

    List<String> usedColumns = new ArrayList<String>();
    for (String colName: colIndexMap.keySet()) {
      if (!unusedColumns.contains(colName)) usedColumns.add(colName);
    }
    out.println("<h2><em>USED</em> Column headings ("+usedColumns.size()+"):</h2><ul>");
    for (String heading: usedColumns) {
      out.println("<li>"+heading+"</li>");
    }
    out.println("</ul>");

    out.println("<h2><em>Missing photos</em>("+missingPhotos.size()+"):</h2><ul>");
    for (String photo: missingPhotos) {
    	out.println("<li>"+photo+"</li>");
    }
    out.println("</ul>");

    out.println("<h2><em>Found photos</em>("+foundPhotos.size()+"):</h2><ul>");
    for (String photo: foundPhotos) {
    	out.println("<li>"+photo+"</li>");
    }
    out.println("</ul>");

    out.println("<h2><strong> "+numFolderRows+" </strong> Folder Rows</h2>");    

    out.println("<h2>Import completed successfully</h2>");    
    //fs.close();
  }

  public Taxonomy loadTaxonomy0(Row row) {
    String sciName = getString(row, "Taxonomy.scientificName");
    if (sciName==null) return null;
    Taxonomy taxy = myShepherd.getOrCreateTaxonomy(sciName);
    String commonName = getString(row, "Taxonomy.commonName");
    if (commonName!=null) taxy.addCommonName(commonName);
    return taxy;
  }

  public Taxonomy loadTaxonomy1(Row row) {
    String sciName = getString(row, "Occurrence.taxonomy1");
    if (sciName==null) return null;
    return myShepherd.getOrCreateTaxonomy(sciName);
  }

  public static boolean validLatLon(Double lat, Double lon) {
    return ((lat!=null) && (lon!=null) && ((lat!=0.0)&&(lon!=0.0)) );
  }

  public Occurrence loadOccurrence(Row row, Occurrence oldOcc, Encounter enc) {
  	
  	Occurrence occ = getCurrentOccurrence(oldOcc, row);
  	// would love to have a more concise way to write following couplets, c'est la vie

  	Integer individualCount = getInteger(row, "Occurrence.individualCount");
  	if (individualCount!=null) occ.setIndividualCount(individualCount);

  	Double decimalLatitiude = getDouble(row, "Encounter.decimalLatitiude");
  	if (decimalLatitiude!=null) occ.setDecimalLatitude(decimalLatitiude);

  	Double decimalLatitude = getDouble(row, "Encounter.decimalLatitude");
  	Double decimalLongitude = getDouble(row, "Encounter.decimalLongitude");

    if (validLatLon(decimalLatitude,decimalLongitude)) {
      occ.setDecimalLatitude(decimalLatitude);
      occ.setDecimalLongitude(decimalLongitude);
    }

    Double distance = getDouble(row, "Occurrence.distance");
    if (distance!=null) occ.setDistance(distance);

    Taxonomy taxy = loadTaxonomy0(row);
    if (taxy!=null) occ.addTaxonomy(taxy);

    Taxonomy taxy1 = loadTaxonomy1(row);
    if (taxy1!=null) occ.addTaxonomy(taxy1);

  	Long millis = getLong(row, "Encounter.dateInMilliseconds");
    if (millis==null) millis = getLong(row, "Occurrence.dateInMilliseconds");
    if (millis==null) millis = getLong(row, "Occurrence.millis");
  	if (millis!=null) occ.setDateTimeLong(millis);

  	if (enc!=null) {
      occ.addEncounter(enc);
      // overwrite=false on following fromEncs methods
      occ.setLatLonFromEncs(false);
    }

  	return occ;

  }

  public Encounter loadEncounter(Row row, ArrayList<Annotation> annotations, String context) {
  	Encounter enc = new Encounter (annotations);

  	// since we need access to the encounter ID
  	String encID = Util.generateUUID();
  	enc.setEncounterNumber(encID);

  	// Time
  	Integer year = getInteger(row, "Encounter.year");
    if (year==null) year = getInteger(row, "Occurrence.year");
  	if (year!=null) enc.setYear(year);

  	Integer month = getInteger(row, "Encounter.month");
    if (month==null) month = getInteger(row, "Occurrence.month");
  	if (month!=null) enc.setMonth(month);

  	Integer day = getInteger(row, "Encounter.day");
    if (day==null) day = getInteger(row, "Occurrence.day");
  	if (day!=null) enc.setDay(day);

  	Integer hour = getInteger(row, "Encounter.hour");
    if (hour==null) hour = getInteger(row, "Occurrence.hour");
  	if (hour!=null) enc.setHour(hour);

  	String minutes = getIntAsString(row,"Encounter.minutes");
    if (minutes==null) minutes = getIntAsString(row, "Occurrence.minutes");
  	if (minutes!=null) enc.setMinutes(minutes);


    // setting milliseconds last means that (if provided) the exif/millis data will always take precedence
    // if we set it before, enc.setMinutes & others would reset millis
    Long millis = getLong(row, "Encounter.dateInMilliseconds");
    if (millis==null) millis = getLong(row, "Occurrence.dateInMilliseconds");
    if (millis==null) millis = getLong(row, "Occurrence.millis");
    boolean hasTimeCategories = (year!=null || month!=null || day!=null || hour!=null || minutes!=null);
    if (millis!=null) {
      if (hasTimeCategories) enc.setDateInMillisOnly(millis); // does not overwrite day/month/etc
      else enc.setDateInMilliseconds(millis);
    } 


  	// Location
  	Double latitude = getDouble(row,"Encounter.latitude");
    if (latitude==null) latitude = getDouble(row,"Encounter.decimalLatitude");
    if (latitude==null) latitude = getDouble(row,"Occurrence.decimalLatitude");

  	Double longitude = getDouble(row, "Encounter.longitude");
    if (longitude==null) longitude = getDouble(row,"Encounter.decimalLongitude");
    if (longitude==null) longitude = getDouble(row,"Occurrence.decimalLongitude");

    if (validLatLon(latitude,longitude)) {
      enc.setDecimalLatitude(latitude);
      enc.setDecimalLongitude(longitude);
    }


  	String locationID = getString(row, "Encounter.locationID");
  	if (locationID!=null) enc.setLocationID(locationID);

    String country = getString(row, "Encounter.country");
    if (country!=null) enc.setCountry(country);

  	// String fields
  	String otherCatalogNumbers = getStringOrInt(row, "Encounter.otherCatalogNumbers");
  	if (otherCatalogNumbers!=null) enc.setOtherCatalogNumbers(otherCatalogNumbers);

  	String sex = getString(row, "Encounter.sex");
  	if (sex!=null) enc.setSex(sex);

  	String genus = getString(row, "Encounter.genus");
  	if (genus!=null) enc.setGenus(genus);

  	String specificEpithet = getString(row, "Encounter.specificEpithet");
  	if (specificEpithet!=null) enc.setSpecificEpithet(specificEpithet);

  	String submitterOrganization = getString(row, "Encounter.submitterOrganization");
  	if (submitterOrganization!=null) enc.setSubmitterOrganization(submitterOrganization);

  	String submitterName = getString(row, "Encounter.submitterName");
  	if (submitterName!=null) enc.setSubmitterName(submitterName);

  	String occurrenceRemarks = getString(row, "Encounter.occurrenceRemarks");
  	if (occurrenceRemarks!=null) enc.setOccurrenceRemarks(occurrenceRemarks);

  	String occurrenceID = getString(row, "Encounter.occurrenceID");
  	if (occurrenceID==null) occurrenceID = getString(row, "Occurrence.occurrenceID");
  	if (occurrenceID!=null) enc.setOccurrenceID(occurrenceID);

  	String submitterID = getString(row, "Encounter.submitterID");
  	if (submitterID!=null) enc.setSubmitterID(submitterID);

  	String behavior = getString(row, "Encounter.behavior");
  	if (behavior!=null) enc.setBehavior(behavior);

  	String individualID = getIndividualID(row);
  	if (individualID!=null) enc.setIndividualID(individualID);

  	String lifeStage = getString(row, "Encounter.lifeStage");
  	if (lifeStage!=null) enc.setLifeStage(lifeStage);

    String researcherComments = getString(row, "Encounter.researcherComments");
    if (researcherComments!=null) enc.addComments(researcherComments);


  	String verbatimLocality = getString(row, "Encounter.verbatimLocality");
  	if (verbatimLocality!=null) enc.setVerbatimLocality(verbatimLocality);
  	
    String patterningCode = getString(row, "Encounter.patterningCode");
    if (patterningCode!=null) enc.setPatterningCode(patterningCode);
    
  	
  	
  	String nickname = getString(row, "MarkedIndividual.nickname");
    if (nickname==null) nickname = getString(row, "MarkedIndividual.nickName");
  	if (nickname!=null) enc.setAlternateID(nickname);

    String alternateID = getString(row, "Encounter.alternateID");
    if (alternateID!=null) enc.setAlternateID(alternateID);

    
    
    /*
     * Start measurements import
     */
    List<String> measureVals=(List<String>)CommonConfiguration.getIndexedPropertyValues("measurement", context);
    List<String> measureUnits=(List<String>)CommonConfiguration.getIndexedPropertyValues("measurementUnits", context);
    int numMeasureVals=measureVals.size();
    for(int bg=0;bg<numMeasureVals;bg++){
      String colName="Encounter.measurement"+bg;
      Double val = getDouble(row, colName);
    	if (val!=null) {
    		Measurement valMeas = new Measurement(encID, measureVals.get(bg), val, measureUnits.get(bg), "");
    		if (committing) enc.setMeasurement(valMeas, myShepherd);
    		if (unusedColumns!=null) unusedColumns.remove(colName);
    	}
    	
    }
  	/*
  	 * End measurements import
  	 */
    
    
    /*
     * Start Submitter imports
     */
     boolean hasSubmitters=true;
     int startIter=0;
     while(hasSubmitters){
       String colEmail="Encounter.submitter"+startIter+".emailAddress";
       String val=getString(row,colEmail);
       if(val!=null){
         if(myShepherd.getUserByEmailAddress(val.trim())!=null){
           User thisPerson=myShepherd.getUserByEmailAddress(val.trim());
           if((enc.getSubmitters()==null) || !enc.getSubmitters().contains(thisPerson)){
             if (committing) enc.addSubmitter(thisPerson);
             if (unusedColumns!=null) unusedColumns.remove(colEmail);
           }
         }
         else{
           //create a new User
           User thisPerson=new User(val.trim(),Util.generateUUID());
           if (committing) enc.addSubmitter(thisPerson);
           if (unusedColumns!=null) unusedColumns.remove(colEmail);
           
           String colFullName="Encounter.submitter"+startIter+".fullName";
           String val2=getString(row,colFullName);
           if(val2!=null) thisPerson.setFullName(val2.trim());
           if (unusedColumns!=null) unusedColumns.remove(colFullName);
           
           String colAffiliation="Encounter.submitter"+startIter+".affiliation";
           String val3=getString(row,colAffiliation);
           if(val3!=null) thisPerson.setAffiliation(val3.trim()); 
           if (unusedColumns!=null) unusedColumns.remove(colAffiliation);
           
         }
         startIter++;
       }
       else{
         hasSubmitters=false;
       }
     }
  	
    /*
     * End Submitter imports
     */
     
     
     /*
      * Start Photographer imports
      */
      boolean hasPhotographers=true;
      startIter=0;
      while(hasPhotographers){
        String colEmail="Encounter.photographer"+startIter+".emailAddress";
        String val=getString(row,colEmail);
        if(val!=null){
          if(myShepherd.getUserByEmailAddress(val.trim())!=null){
            User thisPerson=myShepherd.getUserByEmailAddress(val.trim());
            if((enc.getPhotographers()==null) ||!enc.getPhotographers().contains(thisPerson)){
              if (committing) enc.addPhotographer(thisPerson);
              if (unusedColumns!=null) unusedColumns.remove(colEmail);
            }
          }
          else{
            //create a new User
            User thisPerson=new User(val.trim(),Util.generateUUID());
            if (committing) enc.addPhotographer(thisPerson);
            if (unusedColumns!=null) unusedColumns.remove(colEmail);
            
            String colFullName="Encounter.photographer"+startIter+".fullName";
            String val2=getString(row,colFullName);
            if(val2!=null) thisPerson.setFullName(val2.trim()); 
            if (unusedColumns!=null) unusedColumns.remove(colFullName);
            
            String colAffiliation="Encounter.photographer"+startIter+".affiliation";
            String val3=getString(row,colAffiliation);
            if(val3!=null) thisPerson.setAffiliation(val3.trim());
            if (unusedColumns!=null) unusedColumns.remove(colAffiliation);
            
            
          }
          startIter++;
        }
        else{
          hasPhotographers=false;
        }
      }
     
     /*
      * End Photographer imports
      */
     
    
  	Double depth = getDouble(row, "Encounter.depth");
  	if (depth!=null) enc.setDepth(depth);

  	String scar = getIntAsString(row, "Encounter.distinguishingScar");
  	if (scar!=null) enc.setDistinguishingScar(scar);


  	// SAMPLES
    TissueSample sample = null;
  	String tissueSampleID = getStringOrInt(row, "TissueSample.sampleID");
    // we need to make sure we have a sampleID whenever we have a microsat marker
    if (tissueSampleID==null) tissueSampleID = getStringOrInt(row, "MicrosatelliteMarkersAnalysis.analysisID");
    // same for sex analysis
    if (tissueSampleID==null) tissueSampleID = getStringOrInt(row, "SexAnalysis.processingLabTaskID");
System.out.println("tissueSampleID=(" + tissueSampleID + ")");
  	if (tissueSampleID!=null) {
      sample = myShepherd.getTissueSample(tissueSampleID, encID);
  		if (sample==null) sample = new TissueSample(enc.getCatalogNumber(), tissueSampleID);
  	}

    String markerAnalysisID = getStringOrInt(row, "MicrosatelliteMarkersAnalysis.analysisID");
    // we need to add uniqueness to the parsed string bc it's a primary key
    // but adding full encID is too long of a string.
    if (markerAnalysisID!=null) markerAnalysisID = markerAnalysisID+"-enc-"+encID.substring(0,Math.min(8,encID.length()));
    if (markerAnalysisID!=null && !myShepherd.isGeneticAnalysis(markerAnalysisID)) {
      markerAnalysisID = markerAnalysisID.replaceAll("_","-");
      MicrosatelliteMarkersAnalysis microMark = myShepherd.getMicrosatelliteMarkersAnalysis(markerAnalysisID);
      if (microMark==null) {
        microMark = new MicrosatelliteMarkersAnalysis(markerAnalysisID, tissueSampleID, encID);
        if (sample!=null) sample.addGeneticAnalysis(microMark);
      } // if microMark was grabbed from Shepherd correctly there is no further data to store.
    }

    String sexAnalID = getStringOrInt(row, "SexAnalysis.processingLabTaskID");
    String sexAnalSex = getString(row, "SexAnalysis.sex");
    if (sexAnalID!=null) {
      // we need to add uniqueness to the parsed string bc it's a primary key
      // but adding full encID is too long of a string.
      sexAnalID = sexAnalID+"-enc-"+encID.substring(0,Math.min(8,encID.length()));
      sexAnalID = sexAnalID.replaceAll("_","-");
    }
    if (sexAnalID!=null && sexAnalSex!=null && !myShepherd.isGeneticAnalysis(sexAnalID)) {
      SexAnalysis sexAnal = myShepherd.getSexAnalysis(sexAnalID);
      if (sexAnal==null) {
        sexAnal = new SexAnalysis(sexAnalID, sexAnalSex, encID, tissueSampleID);
        if (sample!=null) sample.addGeneticAnalysis(sexAnal);
      } else sexAnal.setSex(sexAnalSex);
    }

    if (sample!=null) enc.addTissueSample(sample);
    // END SAMPLES


  	String satelliteTag = getString(row, "SatelliteTag.serialNumber");
  	if (satelliteTag!=null) {
  		SatelliteTag tag = new SatelliteTag("",satelliteTag,""); //note the empty fields. sat tags are weird.
  		enc.setSatelliteTag(tag);
  	}

    String caudalType = getIntAsString(row, "Type caudale Mn");
    if (caudalType!=null) {
      enc.setDynamicProperty("caudal type",caudalType);
    }

  	enc.setState("approved");
  	return enc;
  }


  public Set<String> getColumnFieldsForClass(String className) {
  	Set<String> fieldNames = new HashSet<String>();
  	try {
  		for (String columnHeader: colIndexMap.keySet()) {
  			if (columnHeader.contains(className+".")) {
  				fieldNames.add(columnHeader.split(className+".")[1]); // for Encounter.date returns date
  			}	
  		}
  	} catch (Exception e) {}
  	return fieldNames;
  }

  public ArrayList<Annotation> loadAnnotations(Row row) {

  	if (isFolderRow(row)) return loadAnnotationsFolderRow(row);
  	ArrayList<Annotation> annots = new ArrayList<Annotation>();
  	for (int i=0; i<getNumMediaAssets(); i++) {
  		MediaAsset ma = getMediaAsset(row, i);
  		if (ma==null) continue;

  		String species = getSpeciesString(row);
  		Annotation ann = new Annotation(species, ma);
  		ann.setIsExemplar(true);
  		annots.add(ann);
  		//if (ma!=null && ma.localPath()!=null) foundPhotos.add(ma.localPath().toString());
  	}
  	if (annots.size()>0) {
      for (int i=0; i<annots.size(); i++) {
        String maName = "Encounter.mediaAsset"+i;
        String localPath = getString(row, maName);
        if (localPath!=null) foundPhotos.add(photoDirectory+"/"+localPath);
      }
  	}
  	return annots;
  }

  // for when the provided image filename is actually a folder of images
  private ArrayList<Annotation> loadAnnotationsFolderRow(Row row) {
  	ArrayList<Annotation> annots = new ArrayList<Annotation>();
  	String localPath = getString(row, "Encounter.mediaAsset0");
  	if (localPath==null) return annots;
  	localPath = localPath.substring(0,localPath.length()-1); // removes trailing asterisk
//  	localPath = fixGlobiceFullPath(localPath)+"/";
//  	localPath = localPath.replace(" ","\\ ");
  	String fullPath = photoDirectory+localPath;
  	System.out.println(fullPath);
  	// Globice fix!
  	// now fix spaces
  	File photoDir = new File(fullPath);
    if (!photoDir.exists()||!photoDir.isDirectory()||photoDir.listFiles()==null) {
    	boolean itExists = photoDir.exists();
    	boolean isDirectory = (itExists) && photoDir.isDirectory();
    	boolean hasFiles = isDirectory && photoDir.listFiles()!=null;
    	System.out.println("StandardImport ERROR: loadAnnotationsFolderRow called on non-directory (or empty?) path "+fullPath);
    	System.out.println("		itExists: "+itExists);
    	System.out.println("		isDirectory: "+isDirectory);
    	System.out.println("		hasFiles: "+hasFiles);
      missingPhotos.add(localPath);
      return annots;
    }


	  // if there are keywords we apply to all photos in encounter
	  String keyword0 = getString(row, "Encounter.keyword00");
	  Keyword key0 = (keyword0==null) ? null : myShepherd.getOrCreateKeyword(keyword0);
	  String keyword1 = getString(row, "Encounter.keyword01");
	  Keyword key1 = (keyword1==null) ? null : myShepherd.getOrCreateKeyword(keyword1);

	  String species = getSpeciesString(row);
	  for (File f: photoDir.listFiles()) {
	  	MediaAsset ma = null;
	  	try {
	  		JSONObject assetParams = astore.createParameters(f);
	  		System.out.println("		have assetParams");
	  		assetParams.put("_localDirect", f.toString());
	  		System.out.println("		about to create mediaAsset");
			  ma = astore.copyIn(f, assetParams);
	  	} catch (Exception e) {
	  		System.out.println("IOException creating MediaAsset for file "+f.getPath() + ": " + e.toString());
	  		missingPhotos.add(f.getPath());
	  		continue; // skips the rest of loop for this file
	  	}
	  	if (ma==null) continue;
	  	if (key0!=null) ma.addKeyword(key0);
	  	if (key1!=null) ma.addKeyword(key1);
  		Annotation ann = new Annotation(species, ma);
  		ann.setIsExemplar(true);
  		annots.add(ann);
	  }
	  if (annots.size()>0) foundPhotos.add(fullPath);
	  return annots;
  }

  // capitolizes the final directory in path
  private String fixGlobiceFullPath(String path) {
  	String fixed = capitolizeLastFilepart(path);
  	fixed = removeExtraGlobiceString(fixed);
  	return fixed;
  }

  private String removeExtraGlobiceString(String path) {
  	// we somehow got an extra instance of the word "globice" in the path string, right before a 1
  	return (path.replace("Globice1","1"));
  }

  private String capitolizeLastFilepart(String path) {
  	String[] parts = path.split("/");
  	String lastPart = parts[parts.length-1];
  	String firstPart = path.substring(0, path.indexOf(lastPart));
  	return firstPart + lastPart.toUpperCase();
  }


  // most rows have a single image, but some have an image folder
  private boolean isFolderRow(Row row) {
  	String path = getString(row, "Encounter.mediaAsset0");
  	if (path==null) return false;
  	boolean ans = path.endsWith("*");
  	if (ans) numFolderRows++;
  	return ans;
  }

  public String getSpeciesString(Row row) {
  	String genus = getString(row, "Encounter.genus");
  	String species = getString(row, "Encounter.species");
		String total = genus+" "+species;
		if (total==null||total.equals(" ")) total = "unknown";
		return total;
  }

  public String getIndividualID(Row row) {
  	String indID = getString(row, "Encounter.individualID");
    if (indID==null) indID = getString(row, "MarkedIndividual.individualID");
  	// Cetamada uses single letter names like A
  	//if (indID!=null && indID.length()==1) return "Cetamada-"+indID;
  	return indID;
  }

  public MediaAsset getMediaAsset(Row row, int i) {
    System.out.println("getMediaAsset row is "+row.getRowNum()+" and Encounter.mediaAsset"+i);
  	String localPath = getString(row, "Encounter.mediaAsset"+i);
  	if (localPath==null) return null;
  	localPath = Util.windowsFileStringToLinux(localPath);
  	System.out.println("...localPath is: "+localPath);
  	String fullPath = photoDirectory+"/"+localPath;
  	System.out.println("...fullPath is: "+fullPath);
    String resolvedPath = resolveHumanEnteredFilename(fullPath);
    System.out.println("getMediaAsset resolvedPath is: "+resolvedPath);
    if (resolvedPath==null) {
      missingPhotos.add(fullPath);
      foundPhotos.remove(fullPath);
      return null;
    }
	  File f = new File(resolvedPath);
            MediaAsset existMA = checkExistingMediaAsset(f);
            if (existMA != null) {
                if (!f.getName().equals(existMA.getFilename())) {
                    System.out.println("WARNING: got hash match, but DIFFERENT FILENAME for " + f + " with " + existMA + "; allowing new MediaAsset to be created");
                } else {
                    System.out.println("INFO: " + f + " got hash and filename match on " + existMA);
                    return existMA;
                }
            }

	  // create MediaAsset and return it
	  JSONObject assetParams = astore.createParameters(f);
	  assetParams.put("_localDirect", f.toString());
	  MediaAsset ma = null;
	  try {
	  	ma = astore.copyIn(f, assetParams);
	  } catch (java.io.IOException ioEx) {
	  	System.out.println("IOException creating MediaAsset for file "+resolvedPath + ": " + ioEx.toString());
	  	missingPhotos.add(fullPath);
	  	foundPhotos.remove(fullPath);
                return null;
	  }
          myAssets.put(fileHash(f), ma);

	  // keywording

    ArrayList<Keyword> kws = getKeywordsForAsset(row, i);
    ma.setKeywords(kws);

	  // Keyword keyword = null;
	  // String keywordI = getString(row, "Encounter.keyword"+i);
	  // if (keywordI!=null) keyword = myShepherd.getOrCreateKeyword(keywordI);
	  // String keywordOIKey = "Encounter.keyword0"+i;
	  // String keywordOI = getString(row, keywordOIKey);
	  // if (keywordOI!=null) keyword = myShepherd.getOrCreateKeyword(keywordOI);
	  // if (keyword!=null) ma.addKeyword(keyword);

	  return ma;
  }

    //TODO in a perfect world, we would also check db for assets with same hash!!  but then we need a shepherd.  SIGH
    private MediaAsset checkExistingMediaAsset(File f) {
        String fhash = fileHash(f);
        if (fhash == null) return null;
System.out.println("use existing MA [" + fhash + "] -> " + myAssets.get(fhash));
        return myAssets.get(fhash);
    }

    // a gentle wrapper
    private String fileHash(File f) {
        if (f == null) return null;
        try {
            return Util.fileContentHash(f);
        } catch (IOException iox) {
            System.out.println("StandardImport.fileHash() ignorning " + f + " threw " + iox.toString());
        }
        return null;
    }
  private ArrayList<Keyword> getKeywordsForAsset(Row row, int n) {
    ArrayList<Keyword> ans = new ArrayList<Keyword>();
    int maxAssets = getNumAssets(row);
    int maxKeywords=2;
    int stopAtKeyword = (maxAssets==(n+1)) ? maxKeywords : n; // 
    // we have up to two keywords per row.
    for (int i=n; i<stopAtKeyword; i++) {
      String kwColName = "Encounter.keyword0"+i;
      String kwName = getString(row, kwColName);
      if (kwName==null) continue;
      Keyword kw = myShepherd.getOrCreateKeyword(kwName);
      if (kw!=null) ans.add(kw);
    }
    return ans;
  }

  private int getNumAssets(Row row) {
    int n=0;
    while(getString(row,"Encounter.mediaAsset"+n)!=null) {n++;}
    return n;
  }

  // Checks common human errors in inputing filenames
  // and returns the most similar filename that actually exists on the server
  // returns null if it cannot find a good string
  private String resolveHumanEnteredFilename(String fullPath) {
    if (Util.fileExists(fullPath))      return fullPath;

    String candidatePath = uppercaseJpg(fullPath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    candidatePath = noExtension(candidatePath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    String candidatePath2 = uppercaseBeforeJpg(candidatePath);
    if (Util.fileExists(candidatePath2)) return candidatePath2;

    candidatePath = lowercaseJpg(fullPath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    candidatePath = fixSpaceBeforeJpg(candidatePath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    candidatePath = fixSpaceBeforeDotJpg(candidatePath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    candidatePath = removeSpaceDashSpaceBeforeDot(candidatePath);
    if (Util.fileExists(candidatePath)) return candidatePath;

    return null;
  }

    //not sure how cool this is.  but probably same can be said about all this!
    private String noExtension(String filename) {
        if (filename.matches(".*\\.[^\\./]+$")) return filename;
        return filename + ".jpg";  // :(
    }

  private String uppercaseBeforeJpg(String filename) {
  	// uppercases the section between final slash and .jpg
  	if (filename==null) return null;
  	int indexOfDotJpg = filename.indexOf(".jpg");
  	if (indexOfDotJpg == -1) indexOfDotJpg = filename.indexOf(".JPG");
  	int indexOfLastSlash = filename.lastIndexOf("/");
  	if (indexOfDotJpg==-1 || indexOfLastSlash==-1) return filename;

  	String beforePart      = filename.substring(0,indexOfLastSlash+1);
  	String capitolizedPart = filename.substring(indexOfLastSlash+1,indexOfDotJpg).toUpperCase();
  	String afterPart       = filename.substring(indexOfDotJpg);

  	return (beforePart+capitolizedPart+afterPart);
  }

  private String lowercaseJpg(String filename) {
  	if (filename==null) return null;
  	return (filename.replace(".JPG",".jpg"));
  }
  private String uppercaseJpg(String filename) {
    if (filename==null) return null;
    return (filename.replace(".jpg",".JPG"));
  }
  private String fixSpaceBeforeJpg(String filename) {
    if (filename==null) return null;
    return (filename.replace(" jpg",".jpg"));
  }
  private String fixSpaceBeforeDotJpg(String filename) {
    if (filename==null) return null;
    return (filename.replace(" .jpg",".jpg"));
  }
  private String removeTailingSpace(String filename) {
  	if (filename==null) return null;
  	return (filename.replace(" .jpg", ".jpg"));
  }
  private String removeSpaceDashSpaceBeforeDot(String filename) {
    if (filename==null) return null;
    return (filename.replace(" - .", "."));
  }

	private int getNumMediaAssets() {
		if (numMediaAssets==null) setNumMediaAssets();
		return numMediaAssets.intValue();
	}
	private void setNumMediaAssets() {
		int numAssets = 0;
		for (String col: colIndexMap.keySet()) {
			if ((col != null) && (col.indexOf("mediaAsset")>-1)) numAssets++;
		}
		numMediaAssets=numAssets;
	}




  public MarkedIndividual loadIndividual(Row row, Encounter enc) {

  	boolean newIndividual = false;
  	String individualID = getIndividualID(row);
  	if (individualID==null) return null;

    MarkedIndividual mark = myShepherd.getMarkedIndividualQuiet(individualID);
    if (mark==null) { // new individual
	    mark = new MarkedIndividual(individualID, enc);
	    newIndividual = true;
	  }
	  if (mark==null) {
      System.out.println("StandardImport WARNING: weird behavior. Just made an individual but it's still null.");
      return mark;
    }

	  if (!newIndividual) mark.addEncounterNoCommit(enc);

    String alternateID = getString(row, "Encounter.alternateID");
    if (alternateID!=null) mark.setAlternateID(alternateID);

  	String nickname = getString(row, "MarkedIndividual.nickname");
    if (nickname==null) nickname = getString(row, "MarkedIndividual.nickName");
  	if (nickname!=null) mark.setNickName(nickname);

  	return mark;

  }


  // check if oldOcc is the same occurrence as the occurrence on this row
  // if so, return oldOcc. If not, return parseOccurrence(row)
  public Occurrence getCurrentOccurrence(Occurrence oldOcc, Row row) {
  	String occID = getOccurrenceID(row);
  	if (oldOcc!=null && oldOcc.getOccurrenceID()!=null && oldOcc.getOccurrenceID().equals(occID)) return oldOcc;
  	Occurrence occ = myShepherd.getOrCreateOccurrence(occID);

    return occ;
    // if (isOccurrenceOnRow(oldOcc, row)) return oldOcc;
    // return parseOccurrence(row);
  }




  private void initColIndexVariables(Row firstRow) {
  	colIndexMap = makeColIndexMap(firstRow);
  	unusedColumns = new HashSet<String>();
  	// have to manually copy-in like this because keySet returns a POINTER (!!!)
  	for (String colName: colIndexMap.keySet()) {
  		unusedColumns.add(colName);
  	}
  }

  // Returns a map from each column header to the integer col number
  public static Map<String,Integer> makeColIndexMap(Row firstRow) {
  	Map<String,Integer> colMap = new HashMap<String, Integer>();
  	for (int i=0; i<firstRow.getLastCellNum(); i++) {
  		String colName = getString(firstRow, i);
  		colMap.put(colName, i);
  	}
  	return colMap;
  }

  public String getOccurrenceID(Row row) {
  	return (getString(row,"Occurrence.occurrenceID"));
  }

  public boolean isOccurrenceOnRow(Occurrence occ, Row row) {
    return (occ!=null && !occ.getOccurrenceID().equals(getOccurrenceID(row)));
  }




//   // following 'get' functions swallow errors
  public static Integer getInteger(Row row, int i) {
    try {
      double val = row.getCell(i).getNumericCellValue();
      return new Integer( (int) val );
    }
    catch (Exception e){
      // case for when we have a weird String-Double, which looks like a double in the excel sheet, yet is cast as a String, AND has a leading apostrophe in its stored value that prevents us from parsing it as a number.
      try {
        String str = getString(row, i);
        if (str==null) return null;
        try {
          Integer ans = Integer.parseInt(str);
          return ans;
        } catch (Exception badParse) {
          str = str.substring(1);
          Integer ans2 = Integer.parseInt(str);
          System.out.println("      getInteger SUBSTRINGED and got ans "+ans2);
          return ans2;
        }
      }
      catch (Exception ex) {}

    }
    return null;
  }

  public static Long getLong(Row row, int i) {
    try {
      double val = row.getCell(i).getNumericCellValue();
      return new Long( (long) val );
    }
    catch (Exception e){      
      try {
        String str = getString(row, i);
        if (str==null) return null;
        try {
          Long ans = Long.parseLong(str);
          return ans;
        } catch (Exception badParse) {
          str = str.substring(1);
          Long ans2 = Long.parseLong(str);
          System.out.println("      getLong SUBSTRINGED and got ans "+ans2);
          return ans2;
        }
      }
      catch (Exception ex) {}
}
    return null;
  }
  public static Double getDouble(Row row, int i) {
    try {
      double val = row.getCell(i).getNumericCellValue();
      return new Double( val );
    }
    catch (Exception e){
      // case for when we have a weird String-Double, which looks like a double in the excel sheet, yet is cast as a String, AND has a leading apostrophe in its stored value that prevents us from parsing it as a number.
      try {
        String str = getString(row, i);
        if (str==null) return null;
        System.out.println("EXCEL getDouble string conversion case reached with string "+str);
        try {
          Double ans = Double.parseDouble(str);
          System.out.println("      getDouble string conversion got ans "+ans);
          return ans;
        } catch (Exception badParse) {
          str = str.substring(1);
          Double ans2 = Double.parseDouble(str);
          System.out.println("      getDouble SUBSTRINGED and got ans "+ans2);
          return ans2;
        }
      }
      catch (Exception ex) {}
    }
    return null;
  }

  public static String getString(Row row, int i) {
    try {
      String str = row.getCell(i).getStringCellValue();
      if (str.equals("")) return null;
      return str;
    }
    catch (Exception e) {}
    return null;
  }



  public static Boolean getBooleanFromString(Row row, int i) {
    try {
      String boolStr = getString(row, i).trim().toLowerCase();
      if (boolStr==null || boolStr.equals("")) return null;
      else if (boolStr.equals("yes")) return new Boolean(true);
      else if (boolStr.equals("no")) return new Boolean(false);
    }
    catch (Exception e) {}
    return null;
  }

  public static Date getDate(Row row, int i) {
    try {
      Date date = row.getCell(i).getDateCellValue();
      return date;
    }
    catch (Exception e) {}
    return null;
  }

  public static DateTime getDateTime(Row row, int i) {
    Date date = getDate(row, i);
    if (date == null) return null;
    return new DateTime(date);
  }

  // Below methods are *not* static and work from column names rather than column numbers
  // IMPORTANT: ONLY WORKS IF colIndexMap HAS BEEN INITIALIZED
  public String getString(Row row, String colName) {
    if (!colIndexMap.containsKey(colName)) {
      if (verbose) missingColumns.add(colName);
      return null;
    }
    String ans = getString(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public String getIntAsString(Row row, String colName) {
  	Integer i = getInteger(row,colName);
  	if (i==null) return null;
  	return i.toString();
  }
  public String getStringOrInt(Row row, String colName) {
    if (!colIndexMap.containsKey(colName)) {
      if (verbose) missingColumns.add(colName);
      return null;
    }
    String ans = getStringOrInt(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public static String getStringOrInt(Row row, int i) {
    String ans = getString(row, i);
    if (ans==null) {
      Integer inty = getInteger(row,i);
      if (inty!=null) ans = inty.toString();
    }
    return ans;
  }



  public Integer getInteger(Row row, String colName) {
  	if (!colIndexMap.containsKey(colName)) {
  		if (verbose) missingColumns.add(colName);
  		return null;
  	}
    Integer ans = getInteger(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public Long getLong(Row row, String colName) {
  	if (!colIndexMap.containsKey(colName)) {
  		if (verbose) missingColumns.add(colName);
  		return null;
  	}
    Long ans = getLong(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public Double getDouble(Row row, String colName) {
  	if (!colIndexMap.containsKey(colName)) {
  		if (verbose) missingColumns.add(colName);
  		return null;
  	}
    Double ans = getDouble(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public Date getDate(Row row, String colName) {
  	if (!colIndexMap.containsKey(colName)) {
  		if (verbose) missingColumns.add(colName);
  		return null;
  	}
    Date ans = getDate(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }
  public DateTime getDateTime(Row row, String colName) {
  	if (!colIndexMap.containsKey(colName)) {
  		if (verbose) missingColumns.add(colName);
  		return null;
  	}
    DateTime ans = getDateTime(row, colIndexMap.get(colName));
    if (ans!=null && unusedColumns!=null) unusedColumns.remove(colName);
    return ans;
  }


  // Apache POI, shame on you for making me write this. Shame! Shame! Shame! SHAME!
  // (as if I actually wrote this. thanks stackoverflow!)
  public static boolean isRowEmpty(Row row) {
    if (row == null) return true;
    for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
        Cell cell = row.getCell(c);
        if (cell != null && cell.getCellType() != Cell.CELL_TYPE_BLANK)
            return false;
    }
    return true;
	}


	// This would be cool to put in Encounter or something.
	// tho I'm not immediately sure how we'd get the url context, or determine if we want to include /encounters/ or not
	public static String getEncounterURL(Encounter enc) {
		if (enc==null || enc.getCatalogNumber()==null) return null;
		return "encounters/encounter.jsp?number="+enc.getCatalogNumber();
	}

	// gives us a nice link if we're
	public String getEncounterDisplayString(Encounter enc) {
		if (enc==null) return null;
		if (committing) {
			return "<a href=\""+getEncounterURL(enc)+"\" >"+enc.getCatalogNumber()+"</a>";
		}
		return enc.getCatalogNumber();
	}


  private AssetStore  getAssetStore(Shepherd myShepherd) {

    //return AssetStore.getDefault(myShepherd);
    return AssetStore.getDefault(myShepherd);

    // String assetStorePath="/var/lib/tomcat7/webapps/wildbook_data_dir";
    // // TODO: fix this for flukebook
    // // String assetStoreURL="http://flukebook.wildbook.org/wildbook_data_dir";
    // String assetStoreURL="http://54.71.122.188/wildbook_data_dir";

    // AssetStore as = new LocalAssetStore("Oman Import", new File(assetStorePath).toPath(), assetStoreURL, true);


    // if (committing) {
    //   myShepherd.beginDBTransaction();
    //   myShepherd.getPM().makePersistent(as);
    //   myShepherd.commitDBTransaction();
    //   myShepherd.beginDBTransaction();
    // }

    // return as;
    
  }


    //returns file so you can use .getName() or .lastModified() etc
    public static File importXlsFile(String rootDir) {
        File dir = new File(rootDir, "import");
        try {
            for (final File f : dir.listFiles()) {
                if (f.isFile() && f.getName().matches("WildbookStandardFormat.*\\.xlsx")) return f;
            }
        } catch (Exception ex) {
            System.out.println("ERROR: importXlsFile() rootDir=" + rootDir + " threw " + ex.toString());
            return null;
        }
        System.out.println("WARNING: importXlsFile() could not find 'WildbookStandardFormat*.xlsx' in " + dir);
        return null;
    }


}
