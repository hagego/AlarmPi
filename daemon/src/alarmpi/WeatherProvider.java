package alarmpi;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Class implementing the Callable INtercae to return the weather forecast for today
 * using openweathermap.org
 */
public class WeatherProvider implements Callable<String> {
	
	@Override
	public String call() throws Exception {
		minTemperature   = null;
		maxTemperature   = null;
	
		// get local temperature
		OpenhabClient openhabClient = new OpenhabClient();
		Double temperature = openhabClient.getTemperature();
		log.fine("local temperature retrieved from openhab: "+temperature);
		
		// retrieve hourly forecast from openweathermap.org and check for min/max temperatures
		log.info("retrieving forecast data");
		
		final String BASE_URL_HOURLY    = "http://api.openweathermap.org/data/2.5/forecast?q=";
		final String URL_OPTIONS_HOURLY = "&mode=xml&APPID=f593e17912b28437a5f95565670f8e2b";
		
        HttpURLConnection con = null;
        InputStream       is  = null;
        
		//
		// SAX handler for short-term (hourly) OWM forecast data
		//
		DefaultHandler handlerHourly = new DefaultHandler() {
			private boolean today;
			
			@Override
			public void startElement( String namespaceURI, String localName,String qName, Attributes atts )
			{
			    log.finest( "XML startElement qName: " + qName );
			    for ( int i = 0; i < atts.getLength(); i++ )
			    	log.finest( String.format("Attribut no. %d: %s = %s", i,atts.getQName( i ), atts.getValue( i ) ) );
			    
			    final SimpleDateFormat dateFormat     = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss z");
			    
			    if(qName.equalsIgnoreCase("time")) {
			    	// start of new forecast period
			    	try {
						Calendar start = Calendar.getInstance();
						Calendar end   = Calendar.getInstance();;
						start.setTime(dateFormat.parse(atts.getValue("from")+" GMT"));
						end.setTime(dateFormat.parse(atts.getValue("to")+" GMT"));
						log.finest("found forecast from "+start+" to "+end);
						
						if(start.get(Calendar.DAY_OF_MONTH) == Calendar.getInstance().get(Calendar.DAY_OF_MONTH)) {
							today = true;
						}
						else {
							today = false;
						}

					} catch (ParseException e) {
						log.severe("Unable to parse forecast from/to times");
						log.severe(e.getMessage());
					}
			    	
			    }
			    if(qName.equalsIgnoreCase("temperature")) {
			    	if(today) {
			    		log.fine( "temperature for today: value="+atts.getValue("value")+" unit="+atts.getValue("unit") );
			    		
			    		double value = Double.parseDouble(atts.getValue("value"));
			    		if(atts.getValue("unit").equalsIgnoreCase("kelvin")) {
			    			value -= 273.15;
			    		}
						if( minTemperature==null || value<minTemperature) {
							minTemperature = (int)value;
						}
						if( maxTemperature==null || value>maxTemperature) {
							maxTemperature = (int)value;
						}
			    	}
			    }
			}
			
			@Override
			public void endElement( String namespaceURI, String localName,String qName )
			{
			    if(qName.equalsIgnoreCase("time")) {
			    	today = false;
			    }
			}
		};
		
		SAXParserFactory factory = SAXParserFactory.newInstance();
		SAXParser saxParser;
		try {
			saxParser = factory.newSAXParser();
        	// short-term (hourly) forecast
        	URL url = new URL(BASE_URL_HOURLY + Configuration.getConfiguration().getWeatherLocation()+URL_OPTIONS_HOURLY);
        	log.fine("URL hourly: "+url);
            con = (HttpURLConnection) (url).openConnection();
            con.setRequestMethod("GET");
            con.setDoInput(true);
            con.setDoOutput(true);
            con.connect();
            log.fine( "connected" );

            // Let's read the response
            is = con.getInputStream();
            saxParser.parse(is, handlerHourly);
            log.fine( "short-term (hourly) forecast retrieval done" );
		} catch (ParserConfigurationException | SAXException e) {
			log.severe("Unable to create XML parser: "+e);
		} catch(Throwable t) {
            log.severe("Exception during forecast download");
            log.severe(t.getMessage());
        }
        finally {
            try { is.close(); } catch(Throwable t) {}
            try { con.disconnect(); } catch(Throwable t) {}
        }
		
		log.fine("weather data retrieval done. LocalTemp="+temperature+" min temp="+minTemperature+" max temp="+maxTemperature);
		
		String text = new String();
		if(temperature!=null) {
			text = String.format("Die gemessene Temperatur betraegt %d Grad.",(int)Math.round(temperature));
		}
		
		if(minTemperature!=null && maxTemperature!=null) {
			text += String.format("Die Vorhersage liegt fuer heute zwischen %d und %d Grad.",minTemperature,maxTemperature);
		}
		
		log.info("Weather Announcement text="+text);
		if(!text.isEmpty()) {
			return new TextToSpeech().createTempFile(text,"weather.mp3");
		}
		
		return null;
	}
	
	//
	// private data members
	//
	Integer minTemperature;
	Integer maxTemperature;
	
	private static final Logger log = Logger.getLogger( WeatherProvider.class.getName() );
}
