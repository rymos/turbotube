package io.github.rymos.turbotube.web

import android.app.Activity
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tells YouTube's TV player which video qualities this device can really play.
 *
 * The TV player asks MediaSource.isTypeSupported with extra parameters (width, height, framerate,
 * eotf, ...), the way TV browsers understand them. It expects sane values to be accepted and absurd
 * ones (framerate=9999) to be rejected. Android's WebView ignores these parameters and says yes to
 * everything, so the player trusts none of the answers and falls back to 720p at 30 fps. The script
 * from [script] checks the parameters against the hardware decoders and the connected display.
 *
 * It also reports the display's real pixel size as the screen size. The WebView reports it in
 * density-independent pixels (960 x 540 on a 1080p screen), and the player offers nothing above
 * the screen size.
 */
object PlaybackLimits {
    private val SIZES = listOf(7680 to 4320, 3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)
    private val RATES = listOf(60, 30)

    /** Codec families as the player names them, with their Android MIME type. */
    private val CODECS = mapOf(
        "vp9" to "video/x-vnd.on2.vp9",
        "av1" to "video/av01",
        "avc" to "video/avc",
    )

    /** Without a hardware decoder for a codec, it is only offered up to this, to avoid stutter. */
    private val SOFTWARE_LIMIT = listOf(1280, 720, 30)

    fun script(activity: Activity): String {
        val display = display(activity)
        val mode = display.mode
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && isHardware(it) }
        val codecs = JSONObject()
        for ((name, mime) in CODECS) {
            val caps = decoders.mapNotNull { info ->
                runCatching { info.getCapabilitiesForType(mime).videoCapabilities }.getOrNull()
            }
            val supported = JSONArray()
            for ((w, h) in SIZES) for (fps in RATES) {
                if (caps.any { it.areSizeAndRateSupported(w, h, fps.toDouble()) }) {
                    supported.put(JSONArray(listOf(w, h, fps)))
                }
            }
            if (supported.length() == 0) supported.put(JSONArray(SOFTWARE_LIMIT))
            codecs.put(name, supported)
        }
        val limits = JSONObject()
            .put("screen", JSONArray(listOf(mode.physicalWidth, mode.physicalHeight)))
            .put("hdr", isHdr(display))
            .put("codecs", codecs)
        return "(function(){var L=$limits;\n$PATCH\n})();"
    }

    @Suppress("DEPRECATION")
    private fun display(activity: Activity): Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display!! else activity.windowManager.defaultDisplay

    private fun isHardware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            info.isHardwareAccelerated
        } else {
            !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
        }

    @Suppress("DEPRECATION")
    private fun isHdr(display: Display): Boolean =
        display.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true

    private val PATCH = """
        try {
          ['width', 'availWidth'].forEach(function (k) {
            Object.defineProperty(Screen.prototype, k, { get: function () { return L.screen[0]; }, configurable: true });
          });
          ['height', 'availHeight'].forEach(function (k) {
            Object.defineProperty(Screen.prototype, k, { get: function () { return L.screen[1]; }, configurable: true });
          });
        } catch (e) {}
        if (!window.MediaSource || !MediaSource.isTypeSupported) return;
        var original = MediaSource.isTypeSupported.bind(MediaSource);
        var all = [];
        for (var f in L.codecs) all = all.concat(L.codecs[f]);
        function family(c) {
          if (/^(vp9|vp09)/.test(c)) return 'vp9';
          if (/^av01/.test(c)) return 'av1';
          if (/^(avc1|avc3)/.test(c)) return 'avc';
          return null;
        }
        function isHdrCodec(c) {
          var p = c.split('.');
          var t = p[0] === 'vp09' ? p[6] : (p[0] === 'av01' ? p[7] : undefined);
          return t === '16' || t === '18';
        }
        function positive(v, max) { var n = Number(v); return n > 0 && n <= max; }
        MediaSource.isTypeSupported = function (type) {
          var parts = String(type).split(';').map(function (s) { return s.trim(); });
          var kept = [parts[0]];
          var w = 0, h = 0, fps = 0, fam = null;
          for (var i = 1; i < parts.length; i++) {
            var p = parts[i], eq = p.indexOf('=');
            if (eq < 0) { kept.push(p); continue; }
            var key = p.slice(0, eq).trim().toLowerCase();
            var val = p.slice(eq + 1).trim().replace(/^"|"$/g, '');
            if (key === 'codecs') {
              var list = val.split(',');
              for (var j = 0; j < list.length; j++) {
                var c = list[j].trim().toLowerCase();
                if (isHdrCodec(c) && !L.hdr) return false;
                fam = fam || family(c);
              }
              kept.push(p);
            } else if (key === 'width') { if (!positive(val, 7680)) return false; w = Number(val); }
            else if (key === 'height') { if (!positive(val, 7680)) return false; h = Number(val); }
            else if (key === 'framerate') { if (!positive(val, 240)) return false; fps = Number(val); }
            else if (key === 'bitrate') { if (!positive(val, 200000000)) return false; }
            else if (key === 'channels') { if (!positive(val, 8)) return false; }
            else if (key === 'eotf') {
              if (!(val === 'bt709' || ((val === 'smpte2084' || val === 'arib-std-b67') && L.hdr))) return false;
            }
            else if (key === 'cryptoblockformat') { if (val !== 'subsample') return false; }
            else if (key === 'decode-to-texture') { if (val !== 'true' && val !== 'false') return false; }
            else kept.push(p);
          }
          if (w || h || fps) {
            var big = Math.max(w, h), small = Math.min(w, h);
            var ok = (fam ? L.codecs[fam] : all).some(function (s) {
              return big <= s[0] && small <= s[1] && fps <= s[2];
            });
            if (!ok) return false;
          }
          return original(kept.join('; '));
        };
    """.trimIndent()
}
