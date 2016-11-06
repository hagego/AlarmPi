package alarmpi;

/**
 * A dummy implementation of Light Control doing nothing
 */
public class LightControlNone implements LightControl {

	@Override
	public void off() {
	}
	
	@Override
	public int getCount() {
		return 0;
	}

	@Override
	public void setBrightness(double percentage) {
	}
	
	@Override
	public void setBrightness(int lightId,double percentage) {
	}

	@Override
	public double getBrightness() {
		return 0;
	}
	
	@Override
	public double getBrightness(int lightId) {
		return 0;
	}
	
	@Override
	public void setPwm(int pwmValue) {
	}
	
	@Override
	public void setPwm(int lightId,int pwmValue) {
	}

	@Override
	public int getPwm() {
		return 0;
	}
	
	@Override
	public void dimUp(double finalPercent,int seconds) {
	}
}
