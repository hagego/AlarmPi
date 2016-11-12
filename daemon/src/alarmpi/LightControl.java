package alarmpi;

/**
 * Defines the interface for light control
 */
interface LightControl {
	
	/**
	 * switches the light off
	 */
	void off();
	
	/**
	 * switches the light off
	 * @param lightId ID of the light to switch off
	 */
	void off(int lightId);
	
	/**
	 * returns the number of lights that are available
	 * @return number of available lights
	 */
	int getCount();

	/**
	 * sets the brightness of all lights in percent
	 * @param percentage
	 */
	void setBrightness(double percentage);
	
	/**
	 * sets the brightness of a single light in percent
	 * @param lightId     ID of the light to change (ignored if only one light is controlled)
	 * @param percentage
	 */
	void setBrightness(int lightId,double percentage);
	
	/**
	 * returns the brightness in percent
	 * @return brightness in percent
	 */
	double getBrightness();
	
	/**
	 * returns the brightness in percent
	 * @param lightId    ID of the light
	 * @return brightness in percent
	 */
	double getBrightness(int lightId);
	
	/**
	 * sets the raw PWM value of all lights
	 * @param pwmValue
	 */
	void setPwm(int pwmValue);
	
	/**
	 * sets the raw PWM value of a single light
	 * @param lightId    ID of the light to change (ignored if only one light is controlled)
	 * @param pwmValue
	 */
	void setPwm(int lightId,int pwmValue);
	
	/**
	 * returns the raw PWM value
	 * @return raw PWM value
	 */
	int getPwm();

	/**
	 * dims all lights up from 0 to the specified final brightness 
	 * @param finalPercent final brightness in percent
	 * @param seconds      time from start to final brightness in seconds
	 */
	void dimUp(double finalPercent,int seconds);
}
