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
	
	public CalendarProvider(GoogleCalendar.Mode mode) {
		this.mode = mode;
	}

	@Override
	public String call() throws Exception {
		log.fine("Retrieveing calendar entries for "+mode.toString());
		
		GoogleCalendar calendar = new GoogleCalendar();
		if( calendar.connect() ) {
			List<String> entries = calendar.getCalendarEntries(mode);
			if(entries.size()>0) {
				String text = "";
				if(mode == GoogleCalendar.Mode.TODAY) {
					text = entries.size()>1 ? "Kalendereinträge für heute : " : "Kalendereintrag für heute : ";
				}
				if(mode == GoogleCalendar.Mode.TOMORROW) {
					text = entries.size()>1 ? "Kalendereinträge für morgen : " : "Kalendereintrag für morgen : ";
				}
				
				for(String entry:entries) {
					//text += entry.replace("ü", "ue")+" ";
					text += entry+" ";
				}
				// create mp3 file with this weather announcement
				log.info("preparing Calendar announcement, text="+text);
				return new TextToSpeech().createPermanentFile(text);
			}
			else {
				log.fine("No calendar entries found");
				if(mode == GoogleCalendar.Mode.TOMORROW) {
					return new TextToSpeech().createPermanentFile("Kein Kalendereintrag für morgen");
				}
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
	
	private GoogleCalendar.Mode mode;   // defines if calendar entries shall be retrieved for today ot for tomorrow
}
