package org.ecocean;

import java.util.Date;
import java.util.HashMap;
import java.io.Serializable;
import org.ecocean.SinglePhotoVideo;
import org.ecocean.servlet.ServletUtilities;
import org.joda.time.DateTime;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * <code>User</code> stores information about a contact/user.
 * Examples: photographer, submitter
 * @author Ed Stastny
 */
public class User implements Serializable {
  
  
  private static final long serialVersionUID = -1261710718629763048L;
  // The user's full name
  private String fullName;
  //Primary email address
  private String emailAddress;
  private String hashedEmailAddress;
  // User's snail-mail address/location
  private String physicalAddress;
  //Primary phone number
  private String phoneNumber;
  //Organization or project affiliation
  private String affiliation;
  
  private String userProject;
  private String userStatement;
  private String userURL;
  private SinglePhotoVideo userImage;
  
  //Misc. information about this user
  private String notes;
  //Date of last update of this record, in ms
  private long dateInMilliseconds;
  private long userID;

  private long lastLogin=-1;
  
  	private String username;
  	private String password ;
  	private String salt;
  	private String uuid;
  	
  	//String currentContext;
  	
  	
  	private boolean acceptedUserAgreement=false;
  
  private boolean receiveEmails=true; 

	private HashMap<String,String> social;
  	
  	//JDOQL required empty instantiator
  	public User(){}
  	
  	public User(String fullName, String emailAddress, String physicalAddress, String phoneNumber, String affiliation, String notes) {
  	  uuid=Util.generateUUID();
  	  setFullName(fullName);
  	  setEmailAddress(emailAddress);
  	  setPhysicalAddress(physicalAddress);
  	  setPhoneNumber(phoneNumber);
  	  setAffiliation(affiliation);
  	  setNotes(notes);
  	  RefreshDate();
  	  this.lastLogin=-1;
  	}
  	
  	public User(String username,String password, String salt){
  	  uuid=Util.generateUUID();
  	  setUsername(username);
  	  setPassword(password);
  	  setSalt(salt);
			setReceiveEmails(false);
  	  RefreshDate();
  	  this.lastLogin=-1;
  	}
  	
    public User(String email,String uuid){
      this.uuid=uuid;
      setEmailAddress(email);
      setReceiveEmails(true);
      String salt=ServletUtilities.getSalt().toHex();
      String pass=Util.generateUUID();
      String hashedPassword=ServletUtilities.hashAndSaltPassword(pass, salt);
      setPassword(hashedPassword);
      RefreshDate();
      this.lastLogin=-1;
    }
    
    public User(String uuid){
      this.uuid=uuid;
      setReceiveEmails(false);
      String salt=ServletUtilities.getSalt().toHex();
      String pass=Util.generateUUID();
      String hashedPassword=ServletUtilities.hashAndSaltPassword(pass, salt);
      setPassword(hashedPassword);
      RefreshDate();
      this.lastLogin=-1;
    }

  public void RefreshDate()
  {
    this.dateInMilliseconds = new Date().getTime();
  }

  public String getFullName()
  {
    return this.fullName;
  }
  public void setFullName (String fullName)
  {
    if(fullName!=null){
      this.fullName = fullName;
    }
    else{
      this.fullName=null;
    }
    RefreshDate();
  }

    public String getDisplayName() {
        if (fullName != null) return fullName;
        if (username != null) return username;
        return uuid.substring(0,8);
    }

  public String getEmailAddress ()
  {
    return this.emailAddress;
  }
  public String getHashedEmailAddress ()
  {
    return this.hashedEmailAddress;
  }
  public void setEmailAddress (String emailAddress){
    if(emailAddress!=null){
      this.emailAddress = emailAddress;
      this.hashedEmailAddress = generateEmailHash(emailAddress);
    }
    else{
      this.emailAddress=null;
      //NOTE: we intentionally do NOT null the hashed email address. the hash is a reflection that someone was there, allowing us to count users even if we acknowledge a right-to-forget (GDPR) and remove the email address itself
    }
    RefreshDate();
  }

    public static String generateEmailHash(String addr) {
        if ((addr == null) || (addr.trim().equals(""))) return null;
        return ServletUtilities.hashString(addr.trim().toLowerCase());
    }

  public String getPhysicalAddress ()
  {
    return this.physicalAddress;
  }
  public void setPhysicalAddress (String physicalAddress)
  {
    
    if(physicalAddress!=null){this.physicalAddress = physicalAddress;}
    else{this.physicalAddress=null;}
    RefreshDate();
  }

  public String getPhoneNumber ()
  {
    return this.phoneNumber;
  }
  public void setPhoneNumber (String phoneNumber)
  {
    if(phoneNumber!=null){this.phoneNumber = phoneNumber;}
    else{this.phoneNumber=null;}
    RefreshDate();
  }

  public String getAffiliation ()
  {
    return this.affiliation;
  }
  public void setAffiliation (String affiliation)
  {
    if(affiliation!=null){
      this.affiliation = affiliation;
    }
    else{this.affiliation=null;}
    RefreshDate();
  }

  public String getNotes ()
  {
    return this.notes;
  }
  public void setNotes (String notes)
  {
    this.notes = notes;
    RefreshDate();
  }

  public long getDateInMilliseconds ()
  {
    return this.dateInMilliseconds;
  }




  	public long getUserID() {
  		return userID;
  	}
  	public void setUserID(long userID) {
  		this.userID = userID;
  	}
  	public String getUsername() {
  		return username;
  	}
  	public void setUsername(String username) {
  		this.username = username;
  	}
  	public String getPassword() {
  		return password;
  	}
  	public void setPassword(String password) {
  		this.password = password;
  	}
  	
  	public void setSalt(String salt){this.salt=salt;}
  	public String getSalt(){return salt;}


    public void setUserProject(String newProj) {
      if(newProj!=null){userProject = newProj;}
    else{userProject=null;}
    }
    public String getUserProject(){return userProject;}
    
    public void setUserStatement(String newState) {
      if(newState!=null){userStatement = newState;}
    else{userStatement=null;}
    }
    public String getUserStatement(){return userStatement;}
    
    public SinglePhotoVideo getUserImage(){return userImage;}
    

    public void setUserImage(SinglePhotoVideo newImage) {
      if(newImage!=null){userImage = newImage;}
    else{userImage=null;}
    }
    
    public void setUserURL(String newURL) {
      if(newURL!=null){userURL = newURL;}
    else{userURL=null;}
    }
    public String getUserURL(){return userURL;}
  	
    public long getLastLogin(){
      return lastLogin;
    }
    
    public String getLastLoginAsDateString(){
      if(lastLogin==-1) return null;
      return (new DateTime(this.lastLogin)).toString();
    }
    
    public void setLastLogin(long lastie){this.lastLogin=lastie;}
    

    public boolean getReceiveEmails(){return receiveEmails;}
    public void setReceiveEmails(boolean receive){this.receiveEmails=receive;}
    
    

    public boolean getAcceptedUserAgreement(){return acceptedUserAgreement;}
    
    public void setAcceptedUserAgreement(boolean accept){this.acceptedUserAgreement=accept;}


		public String getSocial(String type) {
			if (social == null) return null;
			return social.get(type);
		}
		public void setSocial(String type, String s) {
        if ((s == null) || s.equals("")) {
            unsetSocial(type);
            return;
        }
        if (social == null) social = new HashMap<String,String>();
        social.put(type, s);
		}
		public void setSocial(String type) {
			unsetSocial(type);
		}
		public void unsetSocial(String type) {
			if (social == null) return;
			social.remove(type);
		}


		//TODO this needs to be dealt with better.  see: rant about saving usernames from forms
		public static boolean isUsernameAnonymous(String uname) {
			return ((uname == null) || uname.equals("") || uname.equals("N/A"));
		}

    //public String getCurrentContext(){return currentContext;}
    //public void setCurrentContext(String newContext){currentContext=newContext;}
		
		public String getUUID() {return uuid;}

    //basically mean uuid-equivalent, so deal
    public boolean equals(final Object u2) {
        if (u2 == null) return false;
        if (!(u2 instanceof User)) return false;
        User two = (User)u2;
        if ((this.uuid == null) || (two == null) || (two.getUUID() == null)) return false;
        return this.uuid.equals(two.getUUID());
    }
    public int hashCode() {  //we need this along with equals() for collections methods (contains etc) to work!!
        if (uuid == null) return Util.generateUUID().hashCode();  //random(ish) so we dont get two users with no uuid equals! :/
        return uuid.hashCode();
    }


    public String toString() {
        return new ToStringBuilder(this)
                .append("uuid", uuid)
                .append("username", username)
                .append("fullName", fullName)
                .toString();
    }

}
