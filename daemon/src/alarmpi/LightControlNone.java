package alarmpi;

/**
 * A dummy implementation of Light Control doing nothing
 */
public class LightControlNone extends LightControl {

	public LightControlNone() {
		super(0,"");
	}
	
	@Override
	public void setOff() {
	}
	
	@Override
	public void setBrightness(double percentage) {
	}
	
	@Override
	public double getBrightness() {
		return 0;
	}
	
	
	@Override
	public void setPwm(int pwmValue) {
	}
	
	@Override
	public int getPwm() {
		return 0;
	}
	
	@Override
	public void dimUp(double finalPercent,int seconds) {
	}
}
