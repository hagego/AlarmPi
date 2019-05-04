package alarmpi;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Converts a text string into a .mp3 file.
 * translates the text using Voice RSS text-to-speech (tts) service (api.voicerss.org)
 * Alternatives can be:
 * MARY tts:             http://mary.dfki.de/
 * Microsoft translator: http://www.microsoft.com/en-us/translator/translatorapi.aspx
 * Ivona:                https://www.ivona.com/
 *                       http://www.voicerss.org/api/
 */
public class TextToSpeech {
	
	
	/**
	 * Creates an mp3 file with the specified text in a permanent directory and returns
	 * the filename. If an mp3 file with the text already exists, no new
	 * file is created but the existing one is re-used (its filename is simply returned).
	 * @param  text text to convert into an mp3 file 
	 * @return filename of the mp3 file with the given text
	 */
	String createPermanentFile(String text) {
		// create a filename based on the hash code of the text
		int hashCode    = text.hashCode();
		String fileName = hashCode+".mp3";
		log.config("createPermanentFile for text "+text+" filename="+fileName);
		File file       = new File(Configuration.getConfiguration().getMpdFileDirectory(),fileName);
		
		// if a file with this text does not already exist => create it
		if(!file.exists()) {
			log.fine("file does not exist - need to convert");
			convert(text,Configuration.getConfiguration().getMpdFileDirectory(),fileName);
		}
		
		// return the filename
		return fileName;
	}
	
	
	/**
	 * Creates or overwrites the specified mp3 file in a temporary directory with
	 * the given text
	 * @param  text       text to convert into mp3 file
	 * @param  filename   filename (no path) of the file to create
	 * @return filename incl. the path to the temp directory
	 */
	String createTempFile(String text,String filename) {
		String tmpDirectory = Configuration.getConfiguration().getMpdTmpSubDir();
		if(!tmpDirectory.endsWith(java.io.File.separator)) {
			tmpDirectory += java.io.File.separator;
		}
		convert(text,Configuration.getConfiguration().getMpdFileDirectory(),tmpDirectory+filename);
		
		return tmpDirectory+filename;
	}
	
	/**
	 * converts the given text into an mp3 file
	 * @param text      text to convert
	 * @param directory directory in which the file must be created
	 * @param fileName  filename of mp3 file to create
	 * @return          true if success, false if not
	 */
	private boolean convert(String text,String directory,String fileName) {
		
		if(!text.isEmpty()) {			
			File file = new File(directory,fileName);
			
			// create mp3 file with this weather announcement
			log.fine("text to speech conversion: text="+text+" filename="+file);
			
			// translate into mp3 stream
			try{
	            text=java.net.URLEncoder.encode(text, "UTF-8");
	            //URL url = new URL("http://translate.google.com/translate_tts?tl=de&ie=UTF-8&q="+text+"&total=1&idx=0&client=alarmpi");
	            URL url = new URL("http://api.voicerss.org/?key=f5d762f987f34397b350af6563ffb818&hl=de-de&c=MP3&src="+text);
	            log.fine("URL="+url);
	            
	            HttpURLConnection urlConn = (HttpURLConnection) url.openConnection();
	            urlConn.addRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/45.0.2454.85 Safari/537.36");
	            InputStream audioSrc   = urlConn.getInputStream();
	            DataInputStream read   = new DataInputStream(audioSrc);
	            OutputStream outstream = new FileOutputStream(file);
	            byte[] buffer = new byte[1024];
	            int len;
	            int count = 0;
	            while ((len = read.read(buffer)) > 0) {
	            	outstream.write(buffer, 0, len);
	            	count++;
	            }
	            outstream.close();
	            log.fine("text2speech readcount="+count);
	            
	            if(count<5) {
	            	// this indicates a problem...
	            	log.severe("text2speech conversion returns less than 1k data");
	            }
	            
	    		// update mpd database
	            SoundControl.getSoundControl().update();
	            
	            return true;
			} catch(IOException e){
				log.severe("Exception in text to speech conversion: "+e.getMessage());
				return false;
			}
		}
		else {
			log.warning("Text to speech conversion called with empty text");
			return false;
		}
	}
	
	// private members
	private static final Logger   log    = Logger.getLogger( TextToSpeech.class.getName() );
}
