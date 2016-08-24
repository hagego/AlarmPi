package alarmpi;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;


/**
 * implements Callable to read calendar entries for today from the Google calendar specified
 * in the configuration file in a separate thread. The entries get converted into a mp3 file
 * and the filename is returned by the call() method
 *
 */
public class CalendarProvider implements Callable<String> {

	@Override
	public String call() throws Exception {
		log.fine("Retrieveing calendar entries for today");
		
		GoogleCalendar calendar = new GoogleCalendar();
		if( calendar.connect() ) {
			List<String> entries = calendar.getCalendarEntriesForToday();
			if(entries.size()>0) {
				String text = entries.size()>1 ? "Kalendereintraege fuer heute : " : "Kalendereintrag fuer heute : ";
				for(String entry:entries) {
					text += entry.replace("ü", "ue")+" ";
				}
				// create mp3 file with this weather announcement
				log.info("preparing Calendar announcement, text="+text);
				return new TextToSpeech().createPermanentFile(text);
			}
			else {
				log.fine("No calendar entries found");
			}
		}
		else {
			log.warning("Unable to connect to calendar");
		}
		
		return new String();
	}
	
	//
	// private data members
	//
	
	private static final Logger log = Logger.getLogger( CalendarProvider.class.getName() );
}
