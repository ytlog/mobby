package com.github.ytlog.mobby.android.device.appfunctions

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFunctionCatalogDeviceTest {
    @Test fun publishedSchemaControlsArgumentsAndResults() {
        val metadata = AppFunctionMetadata("increment", "com.example.provider", true,
            AppFunctionSchemaMetadata("test", "increment", 1),
            listOf(AppFunctionParameterMetadata("count", true, AppFunctionIntTypeMetadata(false))),
            AppFunctionResponseMetadata(AppFunctionIntTypeMetadata(false)), AppFunctionComponentsMetadata(), "Increment")
        val input = AppFunctionJson.parameters(metadata, buildJsonObject { put("count", 3) })
        assertEquals(3, input.getIntOrNull("count"))
        assertTrue(runCatching {
            AppFunctionJson.parameters(metadata, buildJsonObject { put("count", "3") })
        }.isFailure)
        assertTrue(runCatching {
            AppFunctionJson.parameters(metadata, buildJsonObject { put("count", 3); put("extra", true) })
        }.isFailure)
        val returned = AppFunctionData.Builder(metadata.response, metadata.components)
            .setInt(ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE, 4).build()
        assertEquals(JsonPrimitive(4), AppFunctionJson.result(metadata, ExecuteAppFunctionResponse.Success(returned)))
    }

    @Test fun android36QueriesPublishedFunctionsThroughPlatform() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Requires an existing Android 16 emulator", Build.VERSION.SDK_INT >= 36)
        val catalog = AppFunctionCatalog(context)
        assertNotNull(catalog.manager())
        val listing = catalog.list()
        Log.i("AppFunctionCatalogTest", "API=${Build.VERSION.SDK_INT}; listing=$listing")
        if (context.checkSelfPermission(Manifest.permission.EXECUTE_APP_FUNCTIONS) == PackageManager.PERMISSION_GRANTED) {
            assertTrue("Discovery failed: $listing",
                listing is AppFunctionListing.Available ||
                    listing == AppFunctionListing.Unavailable(AppFunctionListing.Reason.SYSTEM_DENIED))
        } else {
            assertEquals(AppFunctionListing.Unavailable(AppFunctionListing.Reason.PERMISSION_DENIED), listing)
        }
    }
}
