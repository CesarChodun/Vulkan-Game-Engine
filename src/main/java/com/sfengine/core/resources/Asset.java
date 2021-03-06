package com.sfengine.core.resources;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Class for managing assets.
 *
 * @author Cezary Chodun
 * @since 25.09.2019
 */
public class Asset {

    private static final Logger logger = Logger.getLogger(Asset.class.getName());

    /** File location on the drive. */
    protected File location;

    /**
     * Creates a new asset with its origin in the given file.
     *
     * @param location Asset origin.
     */
    public Asset(File location) {
        this.location = location;
    }

    /**
     * Creates a new asset with its origin in the given file path.
     *
     * @param path Path to the asset origin file.
     */
    public Asset(String path) {
        this(new File(path));
    }

    /**
     * Retrieves a file from the asset.
     *
     * @param path Path to the file.
     * @return The file obtained from the asset.
     * @throws FileNotFoundException When there is no such file.
     */
    public File get(String path) throws FileNotFoundException {
        File out = new File(location.getAbsolutePath() + "/" + path);

        if (!out.exists()) {
            if (out.isDirectory()) {
                logger.log(Level.INFO, "Failed to locate asset(directory too short). Creating new one.", out);
                out.mkdirs();
            }
            else
                throw new FileNotFoundException(
                        "Failed to locate file: " + location.getAbsolutePath() + "/" + path);
        }
        return out;
    }

    /**
     * Obtains a JSON file from the asset.
     *
     * @param path Path to the JSON file.
     * @return The JSON file.
     * @throws IOException if the file does not exist, is a directory rather than a regular file, or
     *     for some other reason cannot be opened forreading.
     */
    public JSONObject getJSON(String path) throws IOException {
        File file = get(path);

        FileReader fileReader = new FileReader(file);
        JSONObject out = null;
        try {
            out = new JSONObject(new JSONTokener(fileReader));
        } catch (JSONException e) {
            System.err.println(
                    "Failed to load JSON object: \""
                            + path
                            + "\" at location: "
                            + location.getPath().toString());
            e.printStackTrace();
        }
        fileReader.close();

        return out;
    }

    /**
     * Gets the subasset from the asset (asset with the origin in the child folder).
     *
     * @param path The path to new asset file(relative to the parent asset).
     * @return The child asset.
     */
    public Asset getSubAsset(String path) {
        Asset out = new Asset(location.getAbsoluteFile() + File.separator + path);
        out.mkdirs();
        return out;
    }

    /**
     * Obtains all defined configuration assets in the given location.
     *
     * @return a list of configs
     */
    public List<ConfigFile> getAllConfigs() throws IOException {
        List<ConfigFile> out = new ArrayList<>();

        for (File f : location.listFiles()) {
            if (f.isFile() && f.getName().endsWith(ConfigFile.EXT)) {
                out.add(getConfigFile(f.getAbsolutePath().substring(location.getAbsolutePath().length() + File.separator.length())));
            }
        }

        return out;
    }

    private void mkdirs() {
        location.mkdirs();
    }

    /**
     * Tells whether a file with a given name exists.
     *
     * @param path Path to be checked.
     * @return True if the file exists and false otherwise.
     */
    public boolean exists(String path) {
        File out = new File(location.getAbsolutePath() + "/" + path);

        return out.exists();
    }

    /**
     * Location of the asset.
     *
     * @return the location of the asset.
     */
    public File getAssetLocation() {
        return location;
    }

    /**
     * Obtains a configuration file from the asset.
     *
     * @param path Path to the configuration file.
     * @return The configuration file.
     * @throws IOException If an I/O error occurred.
     * @throws AssertionError If failed to create JSON file.
     */
    public ConfigFile getConfigFile(String path) throws IOException, AssertionError {
        return new ConfigFile(this, path);
    }

    /**
     * Creates a new file within the asset.
     *
     * @param fileName Name of the new file.
     * @return True if the file was successfully created and false otherwise.
     * @throws IOException If an I/O error occurred.
     */
    public boolean newFile(String fileName) throws IOException {
        File file = new File(location.getAbsolutePath() + "/" + fileName);
        return file.createNewFile();
    }
}
