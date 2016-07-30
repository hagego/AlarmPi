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
	 * sets the brightness of the light in percent
	 * @param percentage
	 */
	void setBrightness(double percentage);
	
	/**
	 * returns the brightness in percent
	 * @return brightness in percent
	 */
	double getBrightness();
	
	/**
	 * sets the raw PWM value
	 * @param pwmValue
	 */
	void setPwm(int pwmValue);
	
	/**
	 * returns the raw PWM value
	 * @return raw PWM value
	 */
	int getPwm();

	/**
	 * dims the light up from 0 to the specified final brightness 
	 * @param finalPercent final brightness in percent
	 * @param seconds      time from start to final brightness in seconds
	 */
	void dimUp(double finalPercent,int seconds);
}
