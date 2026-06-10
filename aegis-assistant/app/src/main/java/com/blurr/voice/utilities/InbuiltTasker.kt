package com.blurr.voice.utilities

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.blurr.voice.InteractableElement
import com.blurr.voice.ScreenInteractionService

object InbuiltTasker {
    private const val TAG = "InbuiltTasker"

    /**
     * Walks the accessibility node tree recursively to collect all visible nodes.
     */
    private fun collectNodes(node: AccessibilityNodeInfo?, list: MutableList<InteractableElement>) {
        if (node == null) return
        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                list.add(
                    InteractableElement(
                        text = node.text?.toString(),
                        contentDescription = node.contentDescription?.toString(),
                        resourceId = node.viewIdResourceName,
                        className = node.className?.toString(),
                        bounds = bounds,
                        node = node
                    )
                )
            }
            val count = node.childCount
            for (i in 0 until count) {
                try {
                    val child = node.getChild(i)
                    if (child != null) {
                        collectNodes(child, list)
                    }
                } catch (e: Exception) {
                    // Ignore transient exceptions if child node becomes invalid
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during recursive collectNodes in Tasker", e)
        }
    }

    /**
     * Auto clicks the first search result on YouTube.
     */
    fun autoClickFirstYoutubeResult(context: Context): Boolean {
        Log.d(TAG, "autoClickFirstYoutubeResult task running...")
        val svc = ScreenInteractionService.instance ?: return false
        val rootNode = svc.rootInActiveWindow ?: return false
        val list = mutableListOf<InteractableElement>()
        collectNodes(rootNode, list)

        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels
        val minY = (screenHeight * 0.15).toInt()
        val maxY = (screenHeight * 0.85).toInt()

        // Filter clickable elements in the expected video area
        val candidates = list.filter { elem ->
            val center = elem.getCenter()
            center.y in minY..maxY &&
            center.x in (screenWidth * 0.1).toInt()..(screenWidth * 0.9).toInt() &&
            (elem.className?.contains("ViewGroup") == true || 
             elem.className?.contains("RelativeLayout") == true || 
             elem.className?.contains("FrameLayout") == true || 
             elem.node.isClickable)
        }

        val bestCandidate = candidates.firstOrNull { elem ->
            val id = elem.resourceId ?: ""
            val desc = elem.contentDescription ?: ""
            val txt = elem.text ?: ""
            !id.contains("search") && !desc.contains("search") && !txt.contains("search") &&
            !desc.contains("filter") && !txt.contains("filter")
        }

        if (bestCandidate != null) {
            val center = bestCandidate.getCenter()
            svc.clickOnPoint(center.x.toFloat(), center.y.toFloat())
            Log.d(TAG, "Successfully clicked YouTube candidate at (${center.x}, ${center.y})")
            return true
        }

        // Tap fallback (first search result spot on typical devices)
        val fallbackX = screenWidth / 2f
        val fallbackY = screenHeight * 0.4f
        svc.clickOnPoint(fallbackX, fallbackY)
        Log.d(TAG, "YouTube first result click fallback at ($fallbackX, $fallbackY)")
        return true
    }

    /**
     * Auto clicks the shutter button of any camera app.
     */
    fun autoClickCameraShutter(context: Context): Boolean {
        Log.d(TAG, "autoClickCameraShutter task running...")
        val svc = ScreenInteractionService.instance ?: return false
        val rootNode = svc.rootInActiveWindow
        val list = mutableListOf<InteractableElement>()
        if (rootNode != null) {
            collectNodes(rootNode, list)
        }

        val shutterKeywords = listOf("shutter", "capture", "take photo", "take picture", "camera_button", "shutter_button", "click", "camera")
        val matched = list.firstOrNull { elem ->
            val desc = elem.contentDescription?.lowercase() ?: ""
            val id = elem.resourceId?.lowercase() ?: ""
            val txt = elem.text?.lowercase() ?: ""
            shutterKeywords.any { desc.contains(it) || id.contains(it) || txt.contains(it) }
        }

        val displayMetrics = context.resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val screenWidth = displayMetrics.widthPixels

        if (matched != null) {
            val center = matched.getCenter()
            svc.clickOnPoint(center.x.toFloat(), center.y.toFloat())
            Log.d(TAG, "Successfully clicked Camera shutter candidate at (${center.x}, ${center.y})")
            return true
        } else {
            // General layout click (bottom center of camera preview viewport)
            val shutterX = screenWidth / 2f
            val shutterY = screenHeight * 0.88f
            svc.clickOnPoint(shutterX, shutterY)
            Log.d(TAG, "Camera shutter clicked fallback at ($shutterX, $shutterY)")
            return true
        }
    }

    /**
     * General automated screen clicker (similar to Tasker's click activity action).
     * Finds any matching text, description, or id on screen and clicks it.
     */
    fun autoClickElementByText(context: Context, query: String): Boolean {
        Log.d(TAG, "autoClickElementByText task running for '$query'...")
        val svc = ScreenInteractionService.instance ?: return false
        val rootNode = svc.rootInActiveWindow ?: return false
        val list = mutableListOf<InteractableElement>()
        collectNodes(rootNode, list)

        val matched = list.firstOrNull { elem ->
            val textMatch = elem.text?.contains(query, ignoreCase = true) == true
            val descMatch = elem.contentDescription?.contains(query, ignoreCase = true) == true
            val idMatch = elem.resourceId?.contains(query, ignoreCase = true) == true
            textMatch || descMatch || idMatch
        }

        if (matched != null) {
            val center = matched.getCenter()
            svc.clickOnPoint(center.x.toFloat(), center.y.toFloat())
            Log.d(TAG, "InbuiltTasker clicked element matching '$query' at (${center.x}, ${center.y})")
            return true
        }
        
        Log.w(TAG, "InbuiltTasker: Click failed, element matching '$query' not found.")
        return false
    }
}
