#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <cstring>

// API 26's NDK Vulkan stub lacks this Vulkan 1.1 symbol. Resolve it at runtime
// so older Android versions can still use the CPU backend.
extern "C" VKAPI_ATTR void VKAPI_CALL vkGetPhysicalDeviceFeatures2(
    VkPhysicalDevice device, VkPhysicalDeviceFeatures2 *features) {
    static void *vulkan = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    static auto get_features = vulkan ? reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
        dlsym(vulkan, "vkGetPhysicalDeviceFeatures2")) : nullptr;
    if (get_features) {
        get_features(device, features);
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(device, &properties);
        const bool older_than_vulkan_12 = properties.apiVersion < VK_API_VERSION_1_2;
        for (auto *next = static_cast<VkBaseOutStructure *>(features->pNext); next; next = next->pNext) {
            if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES && older_than_vulkan_12) {
                // ggml's Vulkan backend calls core 1.2 timeline semaphore APIs.
                // A 1.1 Adreno driver may advertise equivalent extension features
                // without exporting those core entry points, causing a SIGSEGV.
                reinterpret_cast<VkPhysicalDeviceVulkan11Features *>(next)->storageBuffer16BitAccess = VK_FALSE;
            }
            if (next->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES && older_than_vulkan_12) {
                // This promoted core entry point is absent from 1.1 drivers.
                reinterpret_cast<VkPhysicalDeviceVulkan12Features *>(next)->bufferDeviceAddress = VK_FALSE;
            }
        }
        return;
    }
    vkGetPhysicalDeviceFeatures(device, &features->features);
    for (auto *next = static_cast<VkBaseOutStructure *>(features->pNext); next; next = next->pNext) {
        if (next->sType != VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES) continue;
        auto *v11 = reinterpret_cast<VkPhysicalDeviceVulkan11Features *>(next);
        auto *following = v11->pNext;
        std::memset(v11, 0, sizeof(*v11));
        v11->sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
        v11->pNext = following;
    }
}
