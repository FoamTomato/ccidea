package com.ccidea.plugin.poller

import com.ccidea.plugin.pricing.PricingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil

class CcideaStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Idempotent: starting twice is a no-op.
        AppExecutorUtil.getAppExecutorService().execute {
            // Force Batik / Xerces static initialization to happen with our plugin
            // classloader as thread-context. Without this, Batik's SAXDocumentFactory
            // <clinit> later fails with ClassCastException between the platform and
            // plugin classloaders, and once a class fails its <clinit> the JVM never
            // retries, so all subsequent Lets-Plot rendering is dead.
            ensureBatikInitialized()
            runCatching { PricingService.getInstance().ensureLoaded() }
            runCatching {
                com.ccidea.plugin.data.UsageDataLoader.getInstance().resetOffsets()
            }
            ApplicationManager.getApplication().invokeLater {
                PollerService.getInstance().start()
            }
        }
    }

    private fun ensureBatikInitialized() {
        val pluginCL = CcideaStartupActivity::class.java.classLoader
        val original = Thread.currentThread().contextClassLoader
        // Trigger Batik's SAXDocumentFactory.<clinit> with the plugin classloader as
        // thread-context. JAXP's ServiceLoader then finds the Xerces SAXParserFactory
        // shipped inside batik-css-1.17.jar (org.apache.xerces.jaxp.SAXParserFactoryImpl)
        // via META-INF/services on the SAME classloader, avoiding both the prior
        // ClassCastException and the IllegalAccessException of pinning the JDK-internal
        // class that JDK 21 forbids unnamed-module access to.
        try {
            Thread.currentThread().contextClassLoader = pluginCL
            Class.forName("org.apache.batik.dom.util.SAXDocumentFactory", true, pluginCL)
            Class.forName("org.apache.batik.anim.dom.SAXSVGDocumentFactory", true, pluginCL)
        } catch (t: Throwable) {
            com.intellij.openapi.diagnostic.Logger.getInstance(CcideaStartupActivity::class.java)
                .warn("ccidea: Batik pre-init failed (charts will show error): ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            Thread.currentThread().contextClassLoader = original
        }
    }
}
