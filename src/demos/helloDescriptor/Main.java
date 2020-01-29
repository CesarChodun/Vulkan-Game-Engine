package demos.helloDescriptor;

import core.Application;
import core.Engine;

/**
 * Main class of the hello descriptors demo.
 * 
 * @author Cezary Chodun
 * @since 10.01.2020
 */
public class Main {
	
	public static final String DEMO_NAME = "helloDescriptor";
	
	/**
	 * An example main method that starts the engine,
	 * initializes it, and makes a simple report.
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		
		System.out.println("Running \"Hello Descriptor\" demo.");
		
		// Creates an engine object.
		Engine engine = new Engine();
		
		// Creates a task that will perform the game functionality.
		GameLogic logicTask = new GameLogic(engine);
		
		// Adds the task to the engine queue.
		// It will be invoked on the first thread.
		engine.addTask(logicTask);
		
		try {
			//Starts the engine(on this thread).
			engine.run();
		}
		catch (Exception e) {
			System.err.println("Engine was shut down due to a problem:");
			e.printStackTrace();
		}
		finally {
			// Frees the application data
			Application.destroy();
			System.out.println("Engine successfully shut down.");
		}
		
	}
}