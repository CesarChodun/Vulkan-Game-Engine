package com.sfengine.core.rendering;

import static com.sfengine.core.rendering.RenderUtil.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

import com.sfengine.core.context.ContextDictionary;
import com.sfengine.core.context.ContextUtil;
import com.sfengine.core.context.renderjob.RenderJobContext;
import com.sfengine.core.rendering.factories.FrameBufferFactory;
import com.sfengine.core.rendering.factories.SwapchainFactory;
import com.sfengine.core.rendering.recording.RenderJob;
import com.sfengine.core.result.VulkanException;
import com.sfengine.core.result.VulkanResult;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import com.sfengine.core.synchronization.Dependency;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

/**
 * Externally synchronized
 *
 * <p>Handles the rendering work. Obtains frames, and submits the work to the GPU.
 *
 * <p>Class usage:
 *
 * <p>There are three important methods in the class:
 *
 * <p>- acquireNextImage() - submitToQueue() - presentKHR()
 *
 * <p>They should be invoked in the above order. And it is application who decides how to divide
 * work in between this calls.
 *
 * @author Cezary Chodun
 * @since 26.09.2019
 */
public class DefaultRenderer implements Renderer {

    /** Targeted window. */
    private Window window;
    /** Vulkan device. */
    private VkDevice device;

    /** Factory for creating command buffers. */
    private RenderJobContext cmdJobs;
    /** Factory for (re)creating swapchain. */
    private SwapchainFactory swapFactory;
    /** Framebuffers factory. */
    private FrameBufferFactory fbFactory;

    /** Create info for image views. */
    private VkImageViewCreateInfo imageViewCreateInfo;
    /** Create info for semaphores reporting image acquisition. */
    protected VkSemaphoreCreateInfo imageAcquireSemaphoreCreateInfo;
    /** Create info for semaphores reporting render task completion. */
    protected VkSemaphoreCreateInfo renderCompleteSemaphoreCreateInfo;
    /** Create info for fence indicating that render was successful. */
    protected VkFenceCreateInfo workDoneFenceInfo;

    /** Current swapchain. */
    private Long swapchain = VK_NULL_HANDLE;
    /** A list of images acquired from surface. */
    private long[] images;
    /** A list of created image views. */
    private long[] imageViews;
    /** A list of frame buffers. */
    private long[] framebuffers;
    /** A list of created command buffers. */
//    private VkCommandBuffer[] commandBuffers;

    /** Acquired images count. */
    private int acquiredImages = 0;

    private List<Integer> renderImageIndices, busyFrames;

    /** Semaphore handles. */
    private long[] imageAcquireSemaphores = new long[0], renderCompleteSemaphores = new long[0];
    /** Array of fence handles. */
    private long[] workDoneFences = new long[0];

    /** Buffer with semaphore handles. */
    private LongBuffer pWaitSemaphores, pSignalSemaphores;
    /** Buffer with pointers to the command buffers. */
    private PointerBuffer pCommandBuffers;

    /** Queue submit info. */
    protected VkSubmitInfo submitInfo;
    /** KHR present info. */
    protected VkPresentInfoKHR presentInfo;

    /** Queue transferring the rendering work. */
    private VkQueue queue;
    // Must be freed
    /** Buffer with the swapchain handle. */
    private LongBuffer pSwapchain;
    // Must be freed
    /** Buffer for obtaining next image index. */
    private IntBuffer pImageIndex;
    /** Current width and height of the surface images. */
    public static int width, height;
    /**
     * A pointer to an array of pipeline stages at which each corresponding semaphore wait will
     * occur.
     */
    private IntBuffer pWaitDstStageMask;

    /**
     * Creates a new renderer.
     *
     * <p><b>Must</b> be invoked on the first thread!
     *
     * @param window Targeted window.
     * @param imageViewCreateInfo Create info for image views.
     * @param swapchainFactory Swapchain factory.
     * @param dict
     * @param frameBufferFactory
     */
    public DefaultRenderer(
            Window window,
            VkImageViewCreateInfo imageViewCreateInfo,
            SwapchainFactory swapchainFactory,
            FrameBufferFactory frameBufferFactory,
            ContextDictionary dict) {

        this.window = window;
        this.device = ContextUtil.getDevice(dict).getDevice();
        this.queue = ContextUtil.getQueue(dict).getQueue();
        this.imageViewCreateInfo = imageViewCreateInfo;

        if (device.getCapabilities().vkCreateSwapchainKHR == NULL) {
            throw new AssertionError("The device cannot create the swapchain.");
        }

        this.cmdJobs = ContextUtil.getRenderJob(dict);
        this.swapFactory = swapchainFactory;
        this.fbFactory = frameBufferFactory;

        initRenderingResources();
    }

    /** Initializes required resources for renderer. */
    private void initRenderingResources() {
        imageAcquireSemaphoreCreateInfo =
                VkSemaphoreCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                        .pNext(NULL)
                        .flags(0);
        renderCompleteSemaphoreCreateInfo =
                VkSemaphoreCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO)
                        .pNext(NULL)
                        .flags(0);

        pWaitSemaphores = memAllocLong(1);
        pSignalSemaphores = memAllocLong(1);
        pCommandBuffers = memAllocPointer(1);

        pSwapchain = memAllocLong(1);

        pImageIndex = memAllocInt(1);
        pImageIndex.put(0).flip();

        presentInfo =
                VkPresentInfoKHR.calloc()
                        .sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR)
                        .pNext(NULL)
                        .swapchainCount(1)
                        .pSwapchains(pSwapchain)
                        .pImageIndices(pImageIndex)
                        .pWaitSemaphores(null)
                        .pResults(null);

        workDoneFenceInfo =
                VkFenceCreateInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                        .pNext(NULL)
                        .flags(0);

        pWaitDstStageMask = memAllocInt(1);
        pWaitDstStageMask.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

        submitInfo =
                VkSubmitInfo.calloc()
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pNext(NULL)
                        .pWaitDstStageMask(pWaitDstStageMask);
    }

    /**
     * Updates the renderer. Recreates the swapchain and corresponding resources.
     *
     * @throws VulkanException When failed to create the fence.
     */
    @Override
    public void update() {
        try {
            recreateSwapchain();
        } catch (VulkanException e) {
            e.printStackTrace();
        }

        cmdJobs.recreateJobs(framebuffers);

//        if (this.commandBuffers != null) {
//            destroyCmdBuffers();
//        }
//        this.commandBuffers = cmdFactory.createCmdBuffers(width, height, framebuffers);

        renderImageIndices = new ArrayList<Integer>(images.length);
        busyFrames = new ArrayList<Integer>(images.length);

        // Clean up
        for (int i = 0; i < imageAcquireSemaphores.length; i++) {
            vkDestroySemaphore(device, imageAcquireSemaphores[i], null);
            imageAcquireSemaphores[i] = VK_NULL_HANDLE;
        }
        for (int i = 0; i < renderCompleteSemaphores.length; i++) {
            vkDestroySemaphore(device, renderCompleteSemaphores[i], null);
            renderCompleteSemaphores[i] = VK_NULL_HANDLE;
        }
        for (int i = 0; i < workDoneFences.length; i++) {
            vkDestroyFence(device, workDoneFences[i], null);
            workDoneFences[i] = VK_NULL_HANDLE;
        }

        // Create
        imageAcquireSemaphores = new long[images.length];
        renderCompleteSemaphores = new long[images.length];
        workDoneFences = new long[images.length];

        for (int i = 0; i < images.length; i++) {
            try {
                workDoneFences[i] = createFence(device, workDoneFenceInfo, null);
            } catch (VulkanException e) {
                throw new AssertionError("Failed to create fence.", e);
            }
        }
    }

    @Override
    public long getSwapchain() {
        return swapchain;
    }

    /**
     * Recreates the swapchain.
     *
     * @throws VulkanException When there is a problem creating the swapchain.
     */
    private void recreateSwapchain() throws VulkanException {

        VkSwapchainCreateInfoKHR createInfo = swapFactory.getCreateInfo(window, swapchain);
        width = createInfo.imageExtent().width();
        height = createInfo.imageExtent().height();

        // Destroying old swapchain.
        if (swapchain != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device, swapchain, null);
        }

        // Create swapchain
        int err = vkCreateSwapchainKHR(device, createInfo, null, pSwapchain);
        VulkanResult.validate(err, "Failed to recreate swapchain!");
        swapchain = pSwapchain.get(0);

        // Extracting image handles from swapchain.
        IntBuffer pSwapchainImageCount = memAllocInt(1);
        err = vkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, null);
        VulkanResult.validate(err, "Failed to enumerate swapchain images!");

        int swapchainImageCount = pSwapchainImageCount.get(0);

        LongBuffer pSwapchainImages = memAllocLong(swapchainImageCount);
        err = vkGetSwapchainImagesKHR(device, swapchain, pSwapchainImageCount, pSwapchainImages);
        VulkanResult.validate(err, "Failed to obtain swapchain images!");

        images = new long[swapchainImageCount];
        for (int i = 0; i < swapchainImageCount; i++) {
            images[i] = pSwapchainImages.get(i);
        }

        if (imageViews != null) {
            destroyImageViews(device);
        }
        imageViews = createImageViews(device, imageViewCreateInfo, images);

        // Create framebuffers
        if (framebuffers != null) {
            fbFactory.destroyFramebuffers(framebuffers);
        }
        framebuffers = fbFactory.createFramebuffers(width, height, imageViews);

        // Clean up
        memFree(pSwapchainImageCount);
        memFree(pSwapchainImages);
        swapFactory.destroyInfo(createInfo);
    }

    /**
     * Tries to acquire a new image from the swapchain.
     *
     * <p><b>Note:</b> <b>Must</b> be synchronized!
     *
     * @return True if the process was successful (there was an image to be acquired) and false
     *     otherwise.
     * @throws VulkanException When there is problem acquiring the image.
     */
    public boolean acquireNextImage() throws VulkanException {

        if (images == null) {
            update();
        }

        // Checking if a next image can be acquired.
        if (acquiredImages >= images.length) {
            return false;
        }
        acquiredImages++;

        long semaphore = createSemaphore(device, imageAcquireSemaphoreCreateInfo, null);

        int[] pImageIndex = new int[1];
        int err =
                vkAcquireNextImageKHR(
                        device,
                        swapchain,
                        0xFFFFFFFFFFFFFFFFL,
                        semaphore,
                        VK_NULL_HANDLE,
                        pImageIndex); // Fences can be used for synchronization.
        VulkanResult.validate(err, "Failed to acquire imageIndex image KHR!");
        int nextImage = pImageIndex[0];

        renderImageIndices.add(nextImage);
        vkDestroySemaphore(device, imageAcquireSemaphores[nextImage], null);
        imageAcquireSemaphores[nextImage] = semaphore;

        return true;
    }

    /**
     * Submits the work to the queue.
     *
     * <p><b>Must</b> be synchronized!
     *
     * @return True if the submit process was successful(there was a frame ready to be submitted),
     *     and false otherwise.
     * @throws VulkanException When failed to create semaphore, or submit to queue.
     */
    public boolean submitToQueue() throws VulkanException {
        if (renderImageIndices.size() == 0) {
            return false;
        }

        Integer imageIndex = renderImageIndices.remove(0);
        RenderJob cmdJob = cmdJobs.getJob(framebuffers[imageIndex]);
        cmdJob.performUpdate();

        if (renderCompleteSemaphores[imageIndex] != VK_NULL_HANDLE) {
            vkDestroySemaphore(device, renderCompleteSemaphores[imageIndex], null);
        }
        renderCompleteSemaphores[imageIndex] =
                createSemaphore(device, renderCompleteSemaphoreCreateInfo, null);

        pWaitSemaphores.put(0, imageAcquireSemaphores[imageIndex]);
        pSignalSemaphores.put(0, renderCompleteSemaphores[imageIndex]);
        pCommandBuffers.put(0, cmdJob.getCMD());

        submitInfo.waitSemaphoreCount(pWaitSemaphores.remaining());
        submitInfo.pWaitSemaphores(pWaitSemaphores);
        submitInfo.pSignalSemaphores(pSignalSemaphores);
        submitInfo.pCommandBuffers(pCommandBuffers);

        vkResetFences(device, workDoneFences[imageIndex]);

        int err = vkQueueSubmit(queue, submitInfo, workDoneFences[imageIndex]);
        VulkanResult.validate(err, "Failed to submit queue work!");

        busyFrames.add(imageIndex);

        return true;
    }

    /**
     * Presents the rendered image to the window.
     *
     * <p><b>Must</b> be synchronized!
     *
     * @return True if the operation was successful(there was a rendered image to present). And
     *     false otherwise.
     * @throws VulkanException When failed to present the image.
     */
    public boolean presentKHR() throws VulkanException {
        if (busyFrames.size() == 0) {
            return false;
        }
        Integer imageIndex = busyFrames.get(0);

        if (vkGetFenceStatus(device, workDoneFences[imageIndex]) != VK_SUCCESS) {
            return false;
        }
        imageIndex = busyFrames.remove(0);

        pSignalSemaphores.put(0, renderCompleteSemaphores[imageIndex]);

        presentInfo.pImageIndices().put(0, imageIndex);
        presentInfo.pWaitSemaphores(pSignalSemaphores);

        int err = vkQueuePresentKHR(queue, presentInfo);
        VulkanResult.validate(err, "Failed to present image!");

        // The image can be acquired again.
        acquiredImages--;

        return true;
    }

    /**
     * Destroys allocated resources.
     *
     * <p>TODO: free swapchain resources.
     */
    @Override
    public void destroy() {
        imageViewCreateInfo.free();

        memFree(pSwapchain);
        memFree(pImageIndex);

        imageAcquireSemaphoreCreateInfo.free();
        renderCompleteSemaphoreCreateInfo.free();

        memFree(pWaitSemaphores);
        memFree(pSignalSemaphores);
        memFree(pCommandBuffers);

        presentInfo.free();

        workDoneFenceInfo.free();

        memFree(pWaitDstStageMask);

        submitInfo.free();
    }

    @Override
    public Dependency getDependency() {
        return null;
    }

    @Override
    public void run() {
        try {
            boolean res = acquireNextImage();
            res = submitToQueue();
            res = presentKHR();
        } catch (VulkanException e) {
            throw new AssertionError("Failed to perform rendering task.", e);
        }
    }
}