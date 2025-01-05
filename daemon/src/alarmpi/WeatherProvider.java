package alarmpi;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonReader;


/**
 * Class implementing the Callable interface to return the weather forecast for today
 * using openweathermap.org
 */
public class WeatherProvider implements Callable<String> {
	
	/**
	 * constructor
	 */
	public WeatherProvider(Integer temperature) {
		this.temperature = temperature;
	}

	/**
	 * builds the URL for OpenWeatherMap
	 * @return URL for OpenWeatherMap
	 */
	URL buildUrl() {
		final String BASE_URL  = "https://api.openweathermap.org/data/3.0/onecall";
		final String API_KEY   = "c38c5b6c88b6c70b98209a0f5427e779"; // PicturePi API key for OpenWeatherMap
		
		URL    url = null;
		Double longitude = Configuration.getConfiguration().getWeatherLocationLongitude();
		Double latitude  = Configuration.getConfiguration().getWeatherLocationLatitude();
		if(longitude==null || latitude==null) {
			log.severe("No longitude or latitude found in configuration file. No weather forecast reported.");
			return null;
		}
		
		
		try {
			url = new URL(String.format("%s?lat=%f&lon=%f&units=metric&lang=de&exclude=current,minutely,hourly,alerts&appid=%s",BASE_URL,latitude,longitude,API_KEY));
		} catch (MalformedURLException e) {
			log.severe("Unable to create URL: MalformedURLException: "+e.getMessage());
		}
		
		return url;
	}
		
	/**
	 * returns the result of the HTTP GET query
	 * @param url complete URL
	 * @return HTTP GET query result as JSON object
	 */
	JsonObject getForecastAsJsonObject(URL url) {
		HttpURLConnection con = null;
		InputStream       is  = null;
		
		try {
			// short-term (hourly) forecast
			String server = "api.openweathermap.org";
			log.fine("URL hourly: "+url);
			con = (HttpURLConnection)url.openConnection();
			con.setRequestMethod("GET");
			con.setDoInput(true);
			con.setDoOutput(true);
			con.setRequestProperty("User-Agent","Mozilla/5.0 ( compatible ) ");
			con.setRequestProperty("Accept","*/*");
			con.connect();
			log.fine( "connected" );

			// check for errors
			if (con.getResponseCode() != HttpURLConnection.HTTP_OK) {
				log.severe("http connection to openweathermap server "+server+" returned "+con.getResponseCode());
				is = con.getErrorStream();
				
				byte b[] = new byte[200];
				is.read(b);
				log.severe("http error text: "+new String(b));
				
				is.close();
			}
			else {
				// Let's read the response
				is = con.getInputStream();
				
				JsonReader reader = Json.createReaderFactory(null).createReader(new InputStreamReader(is,StandardCharsets.UTF_8));
				JsonObject jsonObject = reader.readObject();
				log.finest("Forecast JSON object: "+jsonObject.toString());
				
				return jsonObject;
			}
		} catch (IOException e) {
			log.severe("IO Exception during HTTP retrieval: "+e.getMessage());
		}
		finally {
			try { if(is!=null) {is.close();} } catch(Throwable t) {}
		}

		return null;
	}
		
	boolean parseForecastFromJsonObject(JsonObject jsonObject) {
		log.fine("parsing forecast from JSON object");
		
		JsonArray  jsonDailyArray  = jsonObject.getJsonArray("daily");
		if(jsonDailyArray==null || jsonDailyArray.size()<1) {
			log.severe("Unable to retrieve daily array from forecast");
			return false;
		}
		
		JsonObject jsonDayForecast = jsonDailyArray.getJsonObject(0);
		log.finest("daily forecast: "+jsonDayForecast.toString());
		
		JsonObject jsonObjectTemp = jsonDayForecast.getJsonObject("temp");
		if(jsonObjectTemp==null) {
			log.severe("parseForecastFromJsonObject: No temperature found");
			return false;
		}
		
		JsonNumber temperatureMin = jsonObjectTemp.getJsonNumber("min");
		if(temperatureMin==null) {
			log.severe("parseForecastFromJsonObject: No min temperature found");
			return false;
		}
		
		JsonNumber temperatureMax = jsonObjectTemp.getJsonNumber("max");
		if(temperatureMax==null) {
			log.severe("parseForecastFromJsonObject: No max temperature found");
			return false;
		}

		minTemperature = (int)temperatureMin.doubleValue();
		maxTemperature = (int)temperatureMax.doubleValue();
		
		return true;
	}
		
	
	@Override
	public String call() throws Exception {
		log.fine("WeatherProvider called");

		minTemperature = null;
		maxTemperature = null;
		
		log.fine("local temperature: "+temperature);

		URL url = buildUrl();
		if(url==null) {
			log.severe("Unable to build URL for OpenWeatherMap");
			return null;
		}

		JsonObject jsonObject = getForecastAsJsonObject(url);
		if(jsonObject==null) {
			log.severe("Unable to retrieve forecast from OpenWeatherMap");
			return null;
		}

		if(!parseForecastFromJsonObject(jsonObject)) {
			log.severe("Unable to parse forecast from JSON object");
			return null;
		}
		
		log.fine("weather data retrieval done. LocalTemp="+temperature+" min temp="+minTemperature+" max temp="+maxTemperature);
		
		String text = new String();
		if(temperature!=null) {
			text = String.format("Die gemessene Temperatur betraegt %d Grad.",temperature);
		}
		
		if(minTemperature!=null && maxTemperature!=null) {
			text += String.format("Die Vorhersage liegt fuer heute zwischen %d und %d Grad.",minTemperature,maxTemperature);
		}
		
		log.fine("Weather Announcement text="+text);
		if(!text.isEmpty()) {
			return new TextToSpeech().createTempFile(text,"weather.mp3");
		}
		
		return null;
	}
	
	//
	// private data members
	//
	private Integer temperature    = null;  // measured temperature
	private Integer minTemperature = null;
	private Integer maxTemperature = null;

	
	private static final Logger log = Logger.getLogger( WeatherProvider.class.getName() );
}
