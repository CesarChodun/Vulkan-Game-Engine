package demos.helloCube.rendering;

import java.util.TimerTask;
import org.joml.Vector3f;
import rendering.engine.shader.DescriptorSet;
import rendering.engine.shader.GeneralizedDescriptorValue;

/**
 * Updates the time descriptor.
 *
 * @author Cezary Chodun
 * @since 10.01.2020
 */
public class Transform4fPeriodicalDescriptorUpdater extends TimerTask {

    private GeneralizedDescriptorValue modelDescriptor, cameraDescriptor;
    Integer timeDescriptorIndex;
    
    private ModelTransform3D transform;
    private CameraTransform camera;
    
    private long mili = -1;

    /** @param set the descriptor to be updated. */
    public Transform4fPeriodicalDescriptorUpdater(
            DescriptorSet set,
            ModelTransform3D transform,
            CameraTransform camera,
            String target) {
        modelDescriptor = (GeneralizedDescriptorValue) set.get("Model").getValue(target);
        cameraDescriptor = (GeneralizedDescriptorValue) set.get("Camera").getValue(target);
        this.transform = transform;
        this.camera = camera;
    }

    @Override
    public void run() {
        if (mili == -1) {
            mili = System.currentTimeMillis();
        }
        
        long newTime = System.currentTimeMillis();
        long delta = newTime - mili;
        
        transform.getRotation().rotateAxis(0.006f * delta / 10, new Vector3f(1, 1, 0));
        mili = newTime;

        modelDescriptor.setUniform(0, transform.getTransformation());
        modelDescriptor.update();
        
        cameraDescriptor.setUniform(0, camera.getTransformation());
        cameraDescriptor.update();
    }
}