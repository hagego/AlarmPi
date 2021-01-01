package alarmpi;

import java.lang.invoke.MethodHandles;
import java.util.logging.Logger;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/**
 * Defines the interface for light control
 */
abstract class LightControl {
	
	/**
	 * constructor
	 * @param id unique light id
	 */
	LightControl(int id,String name) {
		this.id   = id;
		this.name = name;
	}
	
	/**
	 * returns the light ID
	 * @return light ID
	 */
	final int getId() {
		return id;
	}
	
	/**
	 * returns the light name
	 * @return light name
	 */
	final String getName() {
		return name;
	}
	
	/**
	 * switches the light off
	 */
	abstract void setOff();
	
	/**
	 * sets the brightness of all lights in percent
	 * @param percentage
	 */
	abstract void setBrightness(double percentage);
	
	/**
	 * returns the brightness in percent
	 * @return brightness in percent
	 */
	abstract double getBrightness();
	
	/**
	 * sets the raw PWM value of all lights
	 * @param pwmValue
	 */
	abstract void setPwm(int pwmValue);
	
	/**
	 * returns the raw PWM value
	 * @return raw PWM value
	 */
	abstract int getPwm();

	/**
	 * dims all lights up from 0 to the specified final brightness 
	 * @param finalPercent final brightness in percent
	 * @param seconds      time from start to final brightness in seconds
	 */
	abstract void dimUp(double finalPercent,int seconds);
	
	/**
	 * Creates a JsonObject representation of the alarm
	 * @return JsonObject representation of the alarm
	 */
	final JsonObject toJasonObject() {
		JsonObjectBuilder builder = Json.createBuilderFactory(null).createObjectBuilder();
		builder.add("id", id);
		builder.add("name", name);
		builder.add("brightness", (int)getBrightness());
		
		JsonObject jsonObject = builder.build();
		
		return jsonObject;
	}

	/**
	 * parses a JSON array with light objects and sets the brightness of this light accordingly if it is contained in the array
	 * @param jsonArray JSON array with light obejcts
	 */
	final void parseFromJsonArray(JsonArray jsonArray) {
		jsonArray.stream().filter(light -> light.asJsonObject().getInt("id")==id).forEach(light -> {
			log.fine("setting brightness for light "+name+" based on JSON object to "+light.asJsonObject().getInt("brightness"));
			setBrightness(light.asJsonObject().getInt("brightness"));
		});
	}
	
	private static final Logger   log     = Logger.getLogger( MethodHandles.lookup().lookupClass().getName() );
	
	private final int    id;   // light ID
	private final String name; // light name
}
