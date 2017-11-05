package alarmpi;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.store.FileDataStoreFactory;
//import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.CalendarList;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.Events;

/**
 * This class implements access to a Google Calendar
 */
public class GoogleCalendar {


	/**
	 * Connects to Google Calendar
	 * @return true if connect was successful, otherwise false
	 */
    boolean connect() {
    	try {
			httpTransport = GoogleNetHttpTransport.newTrustedTransport();
			jsonFactory = JacksonFactory.getDefaultInstance();
		} catch (GeneralSecurityException | IOException e) {
			log.severe("Unable to create httpTransport: "+e.getMessage());
			httpTransport = null;
			jsonFactory   = null;
			
			return false;
		}
    	
		File dataStoreDir;
		if(Configuration.getConfiguration().getRunningOnRaspberry()) {
			dataStoreDir = new File("/etc/alarmpi", ".google");
		}
		else {
			dataStoreDir = new File(System.getProperty("user.home"), ".store/AlarmPi");
		}
		
		FileDataStoreFactory dataStoreFactory;
	    try {
			dataStoreFactory = new FileDataStoreFactory(dataStoreDir);
		} catch (IOException e) {
			log.severe("Unable to open data store: "+e.getMessage());
			
			return false;
		}
	    

	    Credential credential;	    
	    try {
		    GoogleAuthorizationCodeFlow.Builder b = new GoogleAuthorizationCodeFlow.Builder(httpTransport, jsonFactory, clientId, clientSecret,Collections.singleton(scope));
		    flow      = b.setDataStoreFactory(dataStoreFactory).build();
			credential = flow.loadCredential("alarmpi");
		} catch (IOException e) {
			log.severe("Unable to load credentials: "+e.getMessage());
			
			return false;
		}
	    
	    if(credential==null) {
	    	log.warning("connect called, but not yet authorized.");
	    	return false;
	    }
	    else {
	    	calendar = new com.google.api.services.calendar.Calendar.Builder(httpTransport, jsonFactory, credential)
	    				.setApplicationName("AlarmPi/1.0").build();
	    	return true;
	    }
    }
    
    /**
     * Returns the authorization URL to do the one-time authorization for the access to Google Calendar.
     * If authorization has already been done, null is returned
     * @return authorization URL or null if authorization has already been done
     */
    String getAuthorizationUrl() {
    	// first check if authorization has already been done
    	if(connect()) {
    		log.warning("getAuthorization Url called, but we are already authorized.");
    		return null;
    	}
    	
    	return flow.newAuthorizationUrl().setRedirectUri(redirectUrl).setAccessType("offline").build();
    }
    
    /**
     * 
     * @param code
     * @return
     */
    boolean setAuthorizationCode(String code) {
    	log.info("setting authorization code : >>"+code+"<<");
		try {
			GoogleTokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUrl)
			        .execute();
			Credential credential = flow.createAndStoreCredential(response, "alarmpi");
	    	calendar = new com.google.api.services.calendar.Calendar.Builder(httpTransport, jsonFactory, credential)
			.setApplicationName("AlarmPi/1.0").build();
		} catch (IOException e) {
			log.severe("Error during processing of authorization code: "+e.getMessage());
			return false;
		}
		
		return true;
    }

    /**
     * Returns a list with calendar entries for today
     * @return list with calendar entries for today
     */
    List<String> getCalendarEntriesForToday() {
    	LinkedList<String> entries = new LinkedList<String>();
    	
    	log.fine("Getting calender enries for today");
    	
    	if(calendar==null) {
    		log.warning("getCalendarEntriesForToday called, but Calendar is not connected yet");
    		return entries;
    	}
    	
    	if(Configuration.getConfiguration().getCalendarSummary()==null) {
    		log.warning("getCalendarEntriesForToday called, but no calendar specified in configuration");
    		return entries;
    	}
    	
    	try {
    		// loop thru all calendars and search for the one specified in the configuration
			CalendarList feed = calendar.calendarList().list().execute();
	        if (feed.getItems() != null) {
	            for (CalendarListEntry entry : feed.getItems()) {
	            	log.finest("Found calendar: Summary="+entry.getSummary());
	            	
	            	if(entry.getSummary().equalsIgnoreCase(Configuration.getConfiguration().getCalendarSummary())) {
	            		log.fine("Found specified calendar. Summary="+entry.getSummary()+" ID="+entry.getId());
	            		
	            		com.google.api.services.calendar.Calendar.Events.List request = calendar.events().list(entry.getId());
	            		
	            		// query all calendar events of today
	            		Calendar startOfDay = Calendar.getInstance();
	            		startOfDay.set(Calendar.HOUR_OF_DAY, 0);
	            		startOfDay.set(Calendar.MINUTE, 0);
	            		startOfDay.set(Calendar.SECOND, 0);
	            		Calendar endOfDay = Calendar.getInstance();
	            		endOfDay.set(Calendar.HOUR_OF_DAY, 23);
	            		endOfDay.set(Calendar.MINUTE, 59);
	            		endOfDay.set(Calendar.SECOND, 59);
	            		log.fine("start="+startOfDay+" end="+endOfDay);
	            		
	            		DateTime start= new DateTime(Date.from(startOfDay.toInstant()));
	            		DateTime end  = new DateTime(Date.from(endOfDay.toInstant()));
	            		log.fine("start="+start+" end="+end);
	            		request.setTimeMin(start);
	            		request.setTimeMax(end);
	            		
	            		String pageToken = null;
	            		Events events = null;
	            		
	            		do {
		            		request.setPageToken(pageToken);
		            		events = request.execute();
		            		
		            	    List<Event> items = events.getItems();
		            	    if (items.size() == 0) {
		            	    	log.fine("No calendar items found");
		            	    } else {
		            	        for (Event event : items) {
		            	        	log.fine("Found calendar item: "+event.getSummary());
		            	        	entries.add(event.getSummary());
		            	        }
		            	    }
	            		} while(pageToken!=null);
	            		
	            		break;
	            	}
	            }
	        }
	        else {
	        	log.warning("Calendar List is empty");
	        }
		} catch (IOException e) {
			log.severe("Error during processing of calendar entries: "+e.getMessage());
		}
    	
    	return entries;
    }
 

    
	//
	// private members
	//
    private static final Logger log = Logger.getLogger( Controller.class.getName() );
    
    // clientId and clientSecret for AlarmPi (can be found in Google Developers Console)
    private final String clientId     = "208533358057-qc35dehsvtjvcr1paaj0j81ek1nnf793.apps.googleusercontent.com";
    private final String clientSecret = "skjkiZ3koiIEDVNWXggtkWmu";
    
    // redirect URL for authentification
    private final String redirectUrl = "urn:ietf:wg:oauth:2.0:oob";
    private final String scope       = "https://www.googleapis.com/auth/calendar";
    
    private HttpTransport                               httpTransport;
    private JacksonFactory                              jsonFactory;
    private GoogleAuthorizationCodeFlow                 flow;
    private com.google.api.services.calendar.Calendar   calendar = null;

}
