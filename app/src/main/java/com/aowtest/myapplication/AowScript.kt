package com.aowtest.myapplication

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.Closeable
import java.io.File
import java.util.*
import kotlin.random.Random

class AowScript(private val service: MyService, private val data: PointData, private val imageReader: ImageReader) : Closeable {

    private val utcTimeZone = TimeZone.getTimeZone("UTC")

    // 腳本參數
    var waitAdSeconds: Float = 30f
    var stuckAdSeconds: Float = 10f
    var noAdTimes: Int = 5
    var gameStuckSeconds: Float = 30f
    var treasurePeriodSeconds: Float = 3605f
    var minSelfSoldiers: Int = 200
    var detectPeriodSeconds: Float = 1f
        get() =
            if (duringRestart) {
                duringRestart = false
                10f
            } else field
    var heroDeadQuit: Boolean = true
    var finishQuitGame: Boolean = false

    // 腳本變數
    private var date = 0
    private var hasCoin = true
    private var headHunt = false
    private var inAdTimes = 0
    private var lastTreasureTime = 0L
    private var noAdSingleCounter = 0
    private var noAdCounter = 0
    private var hasActionCount = 0
    private var noActionCount = 0
    private var duringRestart = false

    private var exitAdButton : AccessibilityNodeInfo? = null
    private var image: Image? = null
    private var tessBaseAPI: TessBaseAPI = TessBaseAPI().apply {
        setVariable(TessBaseAPI.VAR_CHAR_WHITELIST, "0123456789")
        setVariable("load_system_dawg", TessBaseAPI.VAR_FALSE)
        setVariable("load_freq_dawg", TessBaseAPI.VAR_FALSE)
        pageSegMode = TessBaseAPI.PageSegMode.PSM_RAW_LINE
        init(service.cacheDir.absolutePath + File.separator, "aow", TessBaseAPI.OEM_DEFAULT)
    }

    fun init() {
        val now =  Calendar.getInstance(utcTimeZone)
        date = now.get(Calendar.DAY_OF_MONTH)
        hasCoin = true
        headHunt = false
        inAdTimes = 0
        lastTreasureTime = now.time.time
        noAdSingleCounter = 0
        noAdCounter = 0
    }

    private fun screenShot() {
        val newImage = imageReader.acquireLatestImage()
        if (newImage != null) {
            image?.close()
            image = newImage
        }
    }

    private fun isInGame(window: AccessibilityWindowInfo?): Boolean {
        if (window == null) return false
        return isInGame(window.root, 0)
    }

    private fun nodeTravel(nodeInfo: AccessibilityNodeInfo, arr: IntArray, className: String): Boolean {
        var node = nodeInfo
        for (i in arr.iterator()) {
            node = if (i < 0) node.parent ?: return false else if (node.childCount > i) node.getChild(i) ?: return false else return false
        }
        if (className == node.className?.toString()) {
            exitAdButton = node
            return true
        }
        return false
    }

    private fun isInGame(node: AccessibilityNodeInfo?, level: Int): Boolean {
        var inGame = true
        if (node == null) return true
        val text = node.text
        if (text != null && text.isNotEmpty())
            inGame = false
        val className = node.className
        if (className != null) {
            if (BuildConfig.DEBUG) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                Log.d("ELEM", "${" ".repeat(level)}${node.className} ${node.text} $rect")
            }
            if (text != null) {
                if (text.contains("秒後可獲得獎勵") || text.contains("剩下") && text.contains("秒")) {
                    nodeTravel(node, intArrayOf(-1, -1, 1),  "android.view.View")
                }
                else if (text.contains("瞭解詳情")) {
                    nodeTravel(node, intArrayOf(-1, -1, 0, 0), "android.widget.Button")
                }
                else if (node.className == "android.widget.TextView") {
                    if (!nodeTravel(node, intArrayOf(-1, -1, -1, -1, 1, 0, 0, 0, -1, -1), "android.widget.LinearLayout"))
                        if (!nodeTravel(node, intArrayOf(-1, -1, -1, -1, 0, 0, 0, -1, -1), "android.widget.LinearLayout"))
                            nodeTravel(node, intArrayOf(-1, -1, -1, -1, -1, 1, 0, 0, 0, -1, -1), "android.widget.LinearLayout")
                    if (BuildConfig.DEBUG)
                        Log.d("DEBUG", "exitAdButton: $exitAdButton")
                }
                else if (node.className == "android.widget.Image") {
                    nodeTravel(node, intArrayOf(-1, -1, 0, 0), "android.widget.Button")
                }
            }
            if(className in arrayOf("android.widget.Image", "android.widget.ImageView", "android.widget.Button")) {
                if (BuildConfig.DEBUG)
                    Log.d("TAG", "Not in game: $className")
                inGame = false
            }
        }
        for (i in 0 until node.childCount)
            if (!isInGame(node.getChild(i), level + 1))
                inGame = false
        return inGame
    }

    private fun detectInt(image: Image, rect: Rect): Int? {
        val width = rect.width
        val height = rect.height
        val x = rect.left
        val y = rect.top
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (i in 0 until width) {
            for (j in 0 until height) {
                val color = image.getPixelColor(x + i, y + j)
                b.setPixel(i, j, android.graphics.Color.rgb(color.r, color.g, color.b))
            }
        }
        tessBaseAPI.setImage(b)
        val result = tessBaseAPI.utF8Text
        if (BuildConfig.DEBUG)
            Log.d("TESS", "Text recognized as $result")
        return result.toIntOrNull()
    }

    fun tick() {
        val calendar = Calendar.getInstance(utcTimeZone)
        val date = calendar.get(Calendar.DAY_OF_MONTH)
        val now = calendar.time.time
        if (now < service.pauseUntil) {
            Log.d("PAUSE", "$now < ${service.pauseUntil}")
            return
        }
        if (date != this.date) {
            this.date = date
            hasCoin = true
            headHunt = true
        }
        var hasAction = true

        val windows = service.windows
        val targetRoot = service.rootInActiveWindow
        var targetWindow = targetRoot?.window
        if (targetWindow?.type != AccessibilityWindowInfo.TYPE_APPLICATION) targetWindow = null
        if (service.currentPackage == "com.addictive.strategy.army" && (service.currentActivity == "com.addictive.strategy.army.UnityPlayerActivity" || isInGame(targetWindow))) {
            screenShot()
            exitAdButton = null
            if (BuildConfig.DEBUG)
                Log.d("DEBUG", "In Game!!")
            inAdTimes = 0
            val outY = intArrayOf(0)
            if (windows.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM })
                hasAction = false
            else if (now - lastTreasureTime > treasurePeriodSeconds * 1000 && detect(data.logic[0]))
                lastTreasureTime = now
            else if (headHunt && detect(data.logic[1])) ;
            else if (headHunt && detect(data.logic[2])) ;
            else if (!headHunt && detect(data.logic[3])) ;
            else if (hasCoin && detect(data.logic[5])) ;
            else if (detect(data.logic[6], ClickWay.NONE))
                noAd(data.logic[6].rect)
            else if (detect(data.logic[7], ClickWay.NONE))
                noAd(data.logic[7].rect)
            else if (detect(data.logic[8], ClickWay.NONE)) {
                if (detect(data.logic[9], ClickWay.DETECT_RECT)) {
                    hasCoin = false
                    if (finishQuitGame) {
                        Looper.myLooper()?.run { Handler(this) }?.post {
                            service.stop()
                            service.startActivity(Intent(Intent.ACTION_MAIN).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                addCategory(Intent.CATEGORY_HOME)
                            })
                        }
                    }
                }
                click(if (hasCoin) data.logic[8].rect else data.logic[6].rect)
            } else if (detect(data.logic[10])) ;
            else if (detect(data.logic[11])) ;
            else if (detect(data.logic[12])) ;
            else if (detect(data.logic[14])) ;
            else if (detect(data.logic[15])) ;
            else if (heroDeadQuit && detect(data.logic[16], ClickWay.PRESS_BACK));
            else if (detect(data.logic[21]));
            else if (detect(data.logic[13], ClickWay.CLICK, true)) ;
            else if (detect(data.logic[4], ClickWay.PRESS_BACK, true))
                headHunt = false
            else if (detect(data.logic[22], ClickWay.NONE, true, outY)) {
                val rect = Rect(data.logic[22].rect)
                rect.top = outY[0] + rect.top - data.logic[22].point.y
                image?.also {image ->
                    detectInt(image, rect).also {number->
                        if (number != null && number < minSelfSoldiers) {
                            pressBack()
                        }
                    }
                }
            }
            else hasAction = false
        }
        else if (service.currentPackage == "com.android.vending" && service.currentActivity == "com.google.android.finsky.activities.MarketDeepLinkHandlerActivity") {
            inAdTimes++
            pressBack()
        }
        else if (service.currentPackage == "com.addictive.strategy.army" && service.currentActivity == "com.google.android.gms.ads.AdActivity") {
            exitAdButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            inAdTimes++
        }
        else if (service.currentPackage == "com.addictive.strategy.army" && service.currentActivity == "com.facebook.ads.AudienceNetworkActivity") {
            exitAdButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            inAdTimes++
        }
        else {
            hasAction = false
        }
        if (hasAction) {
            hasActionCount++
            noActionCount = 0
        }
        else {
            noActionCount++
            hasActionCount = 0
        }
        if (hasCoin && noActionCount * detectPeriodSeconds > gameStuckSeconds || hasActionCount * detectPeriodSeconds > gameStuckSeconds || noAdCounter > noAdTimes) {
            restart()
        }
        else if (!BuildConfig.DEBUG) {
            (inAdTimes * detectPeriodSeconds).also {
                if (it > 32) {
                    pressBack()
                    if (it > gameStuckSeconds) {
                        restart()
                    }
                }
            }
        }
    }

    private fun restart() {
        service.startActivity(Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            action = "RESTART"
        })
        noActionCount = 0
        hasActionCount = 0
        noAdCounter = 0
        inAdTimes = 0
        duringRestart = true
    }

    private fun click(x: Int, y: Int) {
        if (BuildConfig.DEBUG)
            Log.d("ACTION", "Click ($x, $y)")
        val stroke = GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 1)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        service.dispatchGesture(gesture, null, null)
    }

    private fun click(rect: Rect) {
        click(Random.nextInt(rect.left, rect.left + rect.width + 1),
            Random.nextInt(rect.top, rect.top + rect.height + 1))
    }
    private fun pressBack() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    private fun detect(logic: DetectionLogic, way: ClickWay = ClickWay.CLICK, yRange: Boolean = false, outY: IntArray? = null): Boolean {
        val image = this.image?:return false
        if (way != ClickWay.DETECT_RECT) {
            var conditionMetPoint :Point? = null
            for (i in (if (yRange) (0 until image.height) else (logic.point.y..logic.point.y))) {
                val c = image.getPixelColor(logic.point.x, i)
                val radiusPower =
                        (c.r - logic.color.r) * (c.r - logic.color.r) +
                        (c.g - logic.color.g) * (c.g - logic.color.g) +
                        (c.b - logic.color.b) * (c.b - logic.color.b)
                if (radiusPower <= 25) {
                    conditionMetPoint = Point(logic.point.x, i)
                    outY?.set(0, i)
                    if (BuildConfig.DEBUG)
                        Log.d("DETECT", "Detected (${logic.point.x}, ${i}) has color (${c.r}, ${c.g}, ${c.b})")
                    break
                }
            }
            if (conditionMetPoint == null) return false
            if (way == ClickWay.CLICK) {
                if (!yRange) {
                    click(logic.rect)
                }
                else {
                    click(conditionMetPoint.x, conditionMetPoint.y)
                }
            }
            else if (way == ClickWay.PRESS_BACK) {
                pressBack()
            }
        }
        else {
            for (x in logic.rect.left until logic.rect.left + logic.rect.width) {
                for (y in logic.rect.top until logic.rect.top + logic.rect.height) {
                    val ic = image.getPixelColor(x, y)
                    if (ic.r != logic.color.r || ic.g != logic.color.g || ic.b != logic.color.b) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun noAd(skipAdRect: Rect) {
        noAdSingleCounter++
        if (noAdSingleCounter * detectPeriodSeconds > waitAdSeconds) {
            noAdSingleCounter = 0
            noAdCounter++
            click(skipAdRect)
        }
    }

    override fun close() {
        tessBaseAPI.end()
    }

}

private enum class ClickWay {
    CLICK,
    PRESS_BACK,
    NONE,
    DETECT_RECT
}

private fun Image.getPixelColor(x: Int, y: Int) : Color {
    val plane = planes[0]
    val buffer = plane.buffer
    val offset = y * plane.rowStride + x * plane.pixelStride
    return Color(
        buffer[offset].toPositiveInt(),
        buffer[offset + 1].toPositiveInt(),
        buffer[offset + 2].toPositiveInt()
    )
}

fun Byte.toPositiveInt() = toInt() and 0xff