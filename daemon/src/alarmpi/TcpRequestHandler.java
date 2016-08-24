package alarmpi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * handler class for TCP client connections
 */
public class TcpRequestHandler implements Runnable {

	public TcpRequestHandler(Controller controller,Socket socket) {
		this.controller    = controller;
		this.clientSocket  = socket;
		
		// create list with all command handlers
		commandHandlerList = new LinkedList<CommandHandler>();
		commandHandlerList.add(new CommandLoglevel());
		commandHandlerList.add(new CommandAlarm());
		commandHandlerList.add(new CommandSound());
		commandHandlerList.add(new CommandLightControl());
		commandHandlerList.add(new CommandTimer());
		commandHandlerList.add(new CommandCalendar());
		commandHandlerList.add(new CommandOpenhab());
	}

	@Override
	public void run() {
		final int BUFFER_SIZE = 100; // size of command buffer
		log.info("client connected from "+clientSocket.getRemoteSocketAddress());

		try {
			boolean exit = false;
			BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
			char[] buffer = new char[BUFFER_SIZE];
			clientStream  = new PrintWriter(clientSocket.getOutputStream(), true);
			
			clientStream.println("connected to AlarmPi");

			// read and process client commands until 'exit' command is received
			while (!exit) {
				clientStream.print("command: ");
				clientStream.flush();
				
				int length = bufferedReader.read(buffer, 0, BUFFER_SIZE);
				if (length <= 0) {
					log.info("client connection terminated");
					exit = true;
				} else if (length == BUFFER_SIZE) {
					log.severe("message from client exceeds max. length of "
							+ BUFFER_SIZE + ". Ignoring...");
				} else {
					String message = new String(buffer, 0, length).trim();
					log.fine("received message from client: " + message);

					// separate command from parameters
					int index = message.indexOf(' ');
					if (index < 0 || index==message.length()-1) {
						// no spaces found - treat complete message as command
						command    = message.toLowerCase();
						parameters = null;
					} else {
						command    = message.substring(0, index).toLowerCase();
						parameters = message.substring(index+1).split(" ");
					}
					log.fine("received command: >>"+command+"<<");
					boolean isQuery = command.endsWith("?");
					if(isQuery) {
						command = command.substring(0, command.length()-1).toLowerCase();
					}

					// process command
					if (command.equals("exit")) {
						clientSocket.close();
					}
					else if (command.equals("help")) {
						String answer = new String("available commands:\n");
						for(CommandHandler handler:commandHandlerList) {
							answer += "  "+handler.getCommandName()+"\n";
						}
						clientStream.println(new ReturnCodeSuccess(answer));
						clientStream.flush();
					}
					else {
						Boolean processed = false;
						Iterator<CommandHandler> it = commandHandlerList.iterator();
						while(it.hasNext()) {
							CommandHandler handler = it.next();
							if(handler.getCommandName().equalsIgnoreCase(command)) {
								log.fine("command match found: "+handler.getCommandName());
								try {
									ReturnCode rc = handler.process(isQuery);
									log.fine("sending answer: >>"+rc+"<<");
									clientStream.println(rc);
									clientStream.flush();
									processed = true;
									break;
								} catch (CommandHandlerException e) {
									clientStream.println(new ReturnCodeError(e.getMessage()));
									clientStream.flush();
									log.info("command execution failed. message was: "+message);
								}
							}
						}
						
						if(!processed) {
							clientStream.println(new ReturnCodeError("Unknown command "+command));
							clientStream.flush();
							log.info("received unknown command: "+command);
						}
					}
				}
			}
		} catch (IOException e) {
			log.info(e.getMessage());
		}
	}
	
	//
	// local private classes for handling of the individual commands
	//
	
	// Exception class - used for all errors
	private class CommandHandlerException extends Exception {
		private static final long serialVersionUID = 5687868525223365791L;
	}
	
	// return code for process method
	private abstract class ReturnCode {
		public ReturnCode(String message) {
			this.message = message;
		}
		
		protected String  message;
	}
	
	private class ReturnCodeSuccess extends ReturnCode {
		public ReturnCodeSuccess() {
			super("");
		}
		public ReturnCodeSuccess(String message) {
			super(message);
		}
		@Override
		public String toString() {
			return "OK\n"+message;		}
	}
	
	private class ReturnCodeError extends ReturnCode {
		public ReturnCodeError(String message) {
			super(message);
		}
		@Override
		public String toString() {
			return "ERROR\n"+message;
		}
	}

	// abstract base class for command handlers
	private abstract class CommandHandler {
		
		public ReturnCode process(final boolean isQuery) throws CommandHandlerException {
			
			if(isQuery) {
				log.finest("calling get for command "+getCommandName());
				return get();
			}
			else {
				log.finest("calling set for command "+getCommandName());
				return set();
			}
		}
		
		protected abstract ReturnCode set() throws CommandHandlerException;
		
		protected abstract ReturnCode get() throws CommandHandlerException;
		
		protected abstract String getCommandName();
	};
	


	/**
	 * loglevel - log level
	 * sets or queries the current log level
	 * syntax   : loglevel <level>
	 * parameter: <level>  new java log level (warning,info,...)
	 */
	private class CommandLoglevel extends CommandHandler {
		
		@Override
		protected String getCommandName() {return "loglevel";}
		
		@Override
		public ReturnCode set() throws CommandHandlerException {
			try {
				LogManager.getLogManager().getLogger("alarmpi").setLevel(Level.parse(parameters[0].toUpperCase()));
				return new ReturnCodeSuccess();
			}
			catch(IllegalArgumentException e) {
				return new ReturnCodeError("invalid log level "+parameters[0]);
			}
		}
		
		@Override
		public ReturnCode get() throws CommandHandlerException{
			return new ReturnCodeSuccess(LogManager.getLogManager().getLogger("alarmpi").getLevel().toString());
		}
	};
	

	/**
	 * alarm - adds or modifies alarms
	 */
	private class CommandAlarm extends CommandHandler {
		
		@Override
		protected String getCommandName() {
			return "alarm";
		}
		
		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters[0].equals("add")) {
				return add();
			}
			else if(parameters[0].equals("modify")) {
				return modify();
			}
			else if(parameters[0].equals("delete")) {
				return delete();
			}
			else if(parameters[0].equals("enable")) {
				return enable();
			}
			else if(parameters[0].equals("stop")) {
				return stop();
			}
			else {
				return new ReturnCodeError("unknown alarm command: "+parameters[0]);
			}
		}
		
		@Override
		public ReturnCode get() throws CommandHandlerException{
			String answer = new String();
			List<Configuration.Alarm> alarmList = Configuration.getConfiguration().getAlarmList();
			for(Configuration.Alarm alarm:alarmList) {
				String weekDays = alarm.weekDays.toString();
				weekDays = weekDays.substring(1, weekDays.length()-1).replaceAll(" ", "");
				if(weekDays.isEmpty()) {
					weekDays = "-";
				}
				answer += String.format("%4d %b %s %s %d %b\n",alarm.id,alarm.enabled,weekDays,alarm.time,alarm.soundId,alarm.oneTimeOnly);
			}
			return new ReturnCodeSuccess(answer);
		}
		
		// individual set commands
		// add - parameters are: <days>,<time>,<sound URL>
		private ReturnCode add() {
			// check parameter count
			if(parameters.length != 4) {
				return new ReturnCodeError("alarm add: invalid parameter count (expected: <weekdays> <time> <soundID>): "+parameters.length);
			}
			// process parameters
			// 1st parameter: list of weekdays
			EnumSet<DayOfWeek> weekDays = EnumSet.noneOf(DayOfWeek.class);
			if(!parameters[1].equals("-")) {
				for(String day:parameters[1].split(",")) {
					try {
						DayOfWeek dayOfWeek = DayOfWeek.valueOf(day.toUpperCase());
						weekDays.add(dayOfWeek);
					}
					catch(IllegalArgumentException e) {
						return new ReturnCodeError("invalid weekday: "+day);
					}
				}
			}
			
			// 2nd parameter: time
			LocalTime time;
			try {
				time = LocalTime.parse(parameters[2]);
			}
			catch(DateTimeParseException e) {
				return new ReturnCodeError("invalid time string: "+parameters[2]);
			}
			
			// 3rd parameter: sound ID
			final Integer sound;
			try {
				sound = Integer.parseInt(parameters[3]);
				log.info("received alarm add, days="+weekDays+" time="+time+" sound ID="+sound);
				
				// execute in separate thread as alarm creation can take several seconds
				threadPool.execute(new Runnable() {
					@Override
					public void run() {
						Configuration.getConfiguration().createAlarm(weekDays,time,sound);
					}
				});
			}
			catch( NumberFormatException e) {
				return new ReturnCodeError("invalid sound ID: "+parameters[3]);
			}
			
			return new ReturnCodeSuccess();
		}
		
		// modify - parameters are: <id> <days> <time> <sound ID> <enabled> <oneTimeOnly>
		private ReturnCode modify() {
			// check parameter count
			if(parameters.length != 7) {
				return new ReturnCodeError("alarm modify: invalid parameter count (expected <id> <days> <time> <sound ID> <enabled> <oneTimeOnly>): "+parameters.length);
			}
			// process parameters
			// 1st parameter: alarm ID
			int id;
			try {
				id = Integer.parseInt(parameters[1]);
			}
			catch(NumberFormatException e) {
				return new ReturnCodeError("unable to parse alarm id from "+parameters[1]);
			}
			
			// 2nd parameter: list of weekdays
			EnumSet<DayOfWeek> weekDays = EnumSet.noneOf(DayOfWeek.class);
			if(!parameters[2].equals("-")) {
				for(String day:parameters[2].split(",")) {
					try {
						DayOfWeek dayOfWeek = DayOfWeek.valueOf(day.toUpperCase());
						weekDays.add(dayOfWeek);
					}
					catch(IllegalArgumentException e) {
						return new ReturnCodeError("invalid weekday: "+day);
					}
				}
			}
			
			// 3rd parameter: time
			LocalTime time;
			try {
				time = LocalTime.parse(parameters[3]);
			}
			catch(DateTimeParseException e) {
				return new ReturnCodeError("invalid time string: "+parameters[3]);
			}
			
			// 4th parameter: sound ID
			int sound  = Integer.parseInt(parameters[4]);
			
			// 5th parameter: enabled flag
			boolean enabled = Boolean.parseBoolean(parameters[5]);
			
			// 6th parameter: oneTimeOnly flag
			boolean oneTimeOnly= Boolean.parseBoolean(parameters[6]);
			
			log.info("received alarm modify, days="+weekDays+" time="+time+" sound ID="+sound+" enabled="+enabled+" oneTimeOnly="+oneTimeOnly);
			
			// execute in separate thread as alarm creation can take quite long
			threadPool.execute(new Runnable() {
				@Override
				public void run() {
					Configuration.getConfiguration().modifyAlarm(id,weekDays,time,sound,enabled,oneTimeOnly);
				}
			});
			
			return new ReturnCodeSuccess();
		}
		
		// enable - parameters are: <id> <enabled>
		private ReturnCode enable() {
			// check parameter count
			if(parameters.length != 3) {
				return new ReturnCodeError("alarm enable: invalid parameter count (expected <id> <enabled>): "+parameters.length);
			}
			
			try {
				// execute in separate thread as alarm creation can take several seconds
				int     id      = Integer.parseInt(parameters[1]);
				boolean enabled = Boolean.parseBoolean(parameters[2]);
				
				threadPool.execute(new Runnable() {
					@Override
					public void run() {
						Configuration.getConfiguration().enableAlarm(id,enabled,true);
					}
				});
			}
			catch(NumberFormatException e) {
				return new ReturnCodeError("unable to parse alarm id from "+parameters[1]);
			}
			
			return new ReturnCodeSuccess();
		}
		
		// delete - parameters are: <id>
		private ReturnCode delete() {
			// check parameter count
			if(parameters.length != 2) {
				return new ReturnCodeError("alarm delete: invalid parameter count (expected <id>): "+parameters.length);
			}
			
			try {
				// execute in separate thread as alarm creation can take several seconds
				int id = Integer.parseInt(parameters[1]);
				threadPool.execute(new Runnable() {
					@Override
					public void run() {
						Configuration.getConfiguration().deleteAlarm(id);
					}
				});
			}
			catch(NumberFormatException e) {
				return new ReturnCodeError("unable to parse alarm id from "+parameters[1]);
			}
			
			return new ReturnCodeSuccess();
		}

		
		// stop - no parameters
		private ReturnCode stop() {
			controller.stopAlarm();
			
			return new ReturnCodeSuccess();
		}
	}
	
	/**
	 * sound - controls sound
	 */
	
	private class CommandSound extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "sound";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null || parameters.length<1) {
				return new ReturnCodeError("sound: missing command (off | play | volume | timer)");
			}
			
			if(parameters[0].equals("on")) {
				return on();
			}
			else if(parameters[0].equals("off")) {
				return off();
			}
			else if(parameters[0].equals("play")) {
				return play();
			}
			else if(parameters[0].equals("volume")) {
				return volume();
			}
			else if(parameters[0].equals("timer")) {
				return timer();
			}
			else {
				return new ReturnCodeError("unknown sound command: "+parameters[0]);
			}
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			String answer = new String();
			
			SoundControl soundControl = SoundControl.getSoundControl();
			answer = String.format("%d %d %d\n", soundControl.getSound()!=null ? soundControl.getSound() :-1,soundControl.getVolume(),controller.getSoundTimer());
			for(Configuration.Sound sound:Configuration.getConfiguration().getSoundList()) {
				answer += String.format("%s %s\n", sound.name,sound.type);
			}
			return new ReturnCodeSuccess(answer);
		}
		
		// individual set commands
		private ReturnCode on() {
			SoundControl.getSoundControl().on();
			return new ReturnCodeSuccess("");
		}
		
		// stop
		private ReturnCode off() {
			SoundControl.getSoundControl().off();
			return new ReturnCodeSuccess("");
		}
		
		// play
		private ReturnCode play() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound play: sound ID missing");
			}
			SoundControl.getSoundControl().on();
			try {
				SoundControl.getSoundControl().playSound(Integer.parseInt(parameters[1]),null,false);
				return new ReturnCodeSuccess("");
			}
			catch(NumberFormatException | IndexOutOfBoundsException e) {
				return new ReturnCodeError("sound play: invalid sound ID");
			}
		}
		
		// volume
		private ReturnCode volume() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound volume: missing volume (0...100)");
			}
			int volume = Integer.parseInt(parameters[1]);
			if(volume<0 || volume>100) {
				return new ReturnCodeError("sound volume: out of range (0...100)");
			}
			SoundControl.getSoundControl().setVolume(volume);
			return new ReturnCodeSuccess("");
		}
		
		// timer
		private ReturnCode timer() {
			if(parameters.length<2) {
				return new ReturnCodeError("sound timer: missing timer value");
			}
			if(parameters[1].equalsIgnoreCase("off")) {
				controller.deleteSoundTimer();
			}
			else {
				try {
					int timer = Integer.parseInt(parameters[1]);
					controller.setSoundTimer(timer);
				}
				catch(NumberFormatException e) {
					return new ReturnCodeError("sound timer: invalid timer value");
				}
			}
			return new ReturnCodeSuccess("");
		}
	};
	
	private class CommandLightControl extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "light";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null || parameters.length!=1) {
				return new ReturnCodeError("light: invalid parameter count ("+parameters.length+"). Syntax: light <pwm value>");
			}
			
			if(parameters[0].equalsIgnoreCase("off")) {
				controller.getLightControl().off();
			}
			else if(parameters[0].equalsIgnoreCase("dim")) {
				controller.getLightControl().dimUp(100, 600);
			}
			else {
				try {
					int percentage = Integer.parseInt(parameters[0]);
					if(percentage>=0) {
						controller.getLightControl().setBrightness(percentage);
					}
					else {
						// debugging only. If number is negative, set raw PWM value
						controller.getLightControl().setPwm(-percentage);
					}
				} catch(NumberFormatException e) {
					return new ReturnCodeError("unable to parse light pwm percentage value");
				}
			}
			
			return new ReturnCodeSuccess();
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			return new ReturnCodeSuccess(String.valueOf(Math.round(controller.getLightControl().getBrightness())));
		}
	};
	
	
	private class CommandTimer extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "timer";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null || parameters.length!=1) {
				return new ReturnCodeError("led: invalid parameter count ("+parameters.length+"). Syntax: timer off | <seconds from now>");
			}
			
			if(parameters[0].equalsIgnoreCase("off")) {
				controller.deleteSoundTimer();
			}
			else {
				try {
					int secondsFromNow = Integer.parseInt(parameters[0]);
					controller.setSoundTimer(secondsFromNow);
				} catch(NumberFormatException e) {
					return new ReturnCodeError("unable to parse timer seconds value");
				}
			}
			
			return new ReturnCodeSuccess();
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			return new ReturnCodeSuccess(String.valueOf(controller.getSoundTimer()));
		}
	};
	
	private class CommandOpenhab extends CommandHandler{

		@Override
		protected String getCommandName() {
			return "openhab";
		}

		@Override
		public ReturnCode set() throws CommandHandlerException{
			if(parameters==null || parameters.length!=1) {
				return new ReturnCodeError("openhab: invalid parameter count. Syntax: openhab <command | query>");
			}

			if(Configuration.getConfiguration().getOpenhabCommands().contains(parameters[0])) {
				
				OpenhabClient client = new OpenhabClient();
				if(parameters[0].endsWith("?")) {
					try {
						String answer = client.sendQuery(parameters[0]);
						log.info("openhab query "+parameters[0]+" returned "+answer);
						
						return new ReturnCodeSuccess(answer);
					} catch (IOException e) {
						return new ReturnCodeError(e.getMessage());
					}
				}
				else {
					String error = new String();
					if( client.sendCommand(parameters[0], error) ) {
						return new ReturnCodeSuccess();
					}
					else {
						return new ReturnCodeError(error);
					}
				}
			}
			
			log.warning("unsupported openhab command: "+parameters[0]);
			return new ReturnCodeError("unsupported command: "+parameters[0]);
		}
		
		@Override
		protected ReturnCode get() throws CommandHandlerException {
			String commands = new String();
			for( String command: Configuration.getConfiguration().getOpenhabCommands() ) {
				commands += command+" ";
			}
			return new ReturnCodeSuccess(commands);
		}
	};
	
	/**
	 * Deals with Google Calendar
	 */
	private class CommandCalendar extends CommandHandler {
		
		@Override
		protected String getCommandName() {return "calendar";}
		
		@Override
		public ReturnCode set() throws CommandHandlerException {
			if(parameters==null || parameters.length>2) {
				return new ReturnCodeError("calendar: invalid parameter count ("+parameters.length+").");
			}
			
			if(parameters[0].equalsIgnoreCase("geturl")) {
				if(calendar==null) {
					calendar = new GoogleCalendar();
				}
				url = calendar.getAuthorizationUrl();
				if(url==null) {
					return new ReturnCodeError("Already authorized");
				}
				return new ReturnCodeSuccess(url);
			}
			else if(parameters[0].equalsIgnoreCase("setcode")) {
				if(url==null) {
					return new ReturnCodeError("Already authorized or geturl not called");
				}
				if(parameters.length!=2) {
					return new ReturnCodeError("usage: setcode <code>");
				}
				if(calendar.setAuthorizationCode(parameters[1])) {
					return new ReturnCodeSuccess("Authorization successfull.");
				}
				else {
					return new ReturnCodeError("Authorization failed. Please refer to logfiles for details.");
				}
			}
			
			return new ReturnCodeError("invalid command: "+parameters[0]);
		}
		
		@Override
		public ReturnCode get() throws CommandHandlerException{
			if(calendar==null) {
				calendar = new GoogleCalendar();
				if(!calendar.connect()) {
					return new ReturnCodeError("Unable to read calendar");
				}
			}
			List<String> entries = calendar.getCalendarEntriesForToday();
			String answer = new String();
			for(String entry:entries) {
				answer += entry+"\n";
			}
			return new ReturnCodeSuccess(answer);
		}
		
		private GoogleCalendar calendar = null;
		private String         url      = null;
	};
	
	//
	// private data members
	//
	private static final Logger log = Logger.getLogger( TcpRequestHandler.class.getName() );
	private final Socket               clientSocket;
	private final Controller           controller;
	private       PrintWriter          clientStream;
	private       String               command;
	private       String[]             parameters;
	private final List<CommandHandler> commandHandlerList;
	final ExecutorService threadPool = Executors.newCachedThreadPool();
}
