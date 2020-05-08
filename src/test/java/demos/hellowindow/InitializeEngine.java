package demos.hellowindow;

import com.sfengine.core.Application;
import com.sfengine.core.EngineTask;
import com.sfengine.core.HardwareManager;
import com.sfengine.core.result.VulkanException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.Semaphore;

/** Initializes the engine. */
public class InitializeEngine implements EngineTask {

    static final String CONFIG_FILE = "demos/hellowindow";
    private Semaphore workDone;

    /** @param workDone A semaphore that will be released after the initialization */
    public InitializeEngine(Semaphore workDone) {
        this.workDone = workDone;
    }

    @Override
    public void run() {
        /*
         *     This method("run()") will be invoked first.
         *    It is guaranteed that it will be run on the first thread.
         *    And this is the place where you want to start programming your game.
         *    You can think about it like a 'main' method.
         *
         */

        try {
            // Initializes the application with the configuration file(CONFIG_FILE)
            Application.init(CONFIG_FILE);

            System.out.println("Application data succesfully initialized!");

        } catch (FileNotFoundException e) {

            System.err.println("Failed to find the configuration file(\"" + CONFIG_FILE + "\")");
            e.printStackTrace();
        }

        try {
            // Initializes the hardware information.
            HardwareManager.init(Application.getApplicationInfo(), Application.getConfigAssets());
            System.out.println("Hardware succesfully initialized!");
        } catch (VulkanException e) {

            System.err.println("Failed to initialize hardware due to a vulkan problem.");
            e.printStackTrace();
        } catch (IOException e) {

            System.err.println("Failed to initialize hardware due to an input(or output) error.");
            e.printStackTrace();
        }

        // Initialization process has been finished so the semaphore is released
        workDone.release();
    }
}