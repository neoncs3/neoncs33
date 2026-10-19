package com.neoncs3

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * NeonHub's own settings page, with only website-source switches.
 */
object NeonHubSettings {
    const val HUB_SOURCE_ASYAFILM = "neonhub_source_asyafilm"
    const val HUB_SOURCE_DIZIMAG = "neonhub_source_dizimag"
    const val HUB_SOURCE_HDFILMDIZI = "neonhub_source_hdfilmdizi"
    const val HUB_SOURCE_CANLI = "neonhub_source_canli"
    const val HUB_SOURCE_TVDIZILER = "neonhub_source_tvdiziler"
    const val HUB_SOURCE_DDIZI = "neonhub_source_ddizi"
    const val HUB_SOURCE_DIZIBOXIZLE = "neonhub_source_diziboxizle"
    const val HUB_SOURCE_DIZIDAD = "neonhub_source_dizidad"
    const val HUB_SOURCE_DIZIPAL = "neonhub_source_dizipal"
    const val HUB_SOURCE_DIZIGECESI = "neonhub_source_dizigecesi"
    const val HUB_SOURCE_DRAMADIZILERIM = "neonhub_source_dramadizilerim"
    const val HUB_SOURCE_FILMMODU = "neonhub_source_filmmodu"
    const val HUB_SOURCE_JETFILMIZLE = "neonhub_source_jetfilmizle"
    const val HUB_SOURCE_SETFILMIZLE = "neonhub_source_setfilmizle"
    const val HUB_SOURCE_SINEMACX = "neonhub_source_sinemacx"
    const val HUB_SOURCE_TIZAM = "neonhub_source_tizam"
    const val HUB_SOURCE_WEBDRAMATURKEY = "neonhub_source_webdramaturkey"

    private val backgroundColor = Color.parseColor("#0B1020")
    private val cardColor = Color.parseColor("#121A2B")
    private val textPrimary = Color.parseColor("#F3F6FF")
    private val textSecondary = Color.parseColor("#A6B0CA")
    private val cyan = Color.parseColor("#00D9FF")
    private val purple = Color.parseColor("#A855F7")
    private val green = Color.parseColor("#34D399")
    private val amber = Color.parseColor("#FBBF24")
    private val red = Color.parseColor("#FB7185")
    private val borderColor = Color.parseColor("#26334D")

    data class Source(val key: String, val title: String, val detail: String)
    private data class SourceProbe(val label: String, val color: Int, val category: Int)

    val sources = listOf(
        Source(HUB_SOURCE_ASYAFILM, "AsyaFilmIzle", "Asya filmleri ve dizileri"),
        Source(HUB_SOURCE_DIZIMAG, "DiziMag", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_HDFILMDIZI, "HDFilmDiziİzle", "HD film ve dizi kataloğu"),
        Source(HUB_SOURCE_CANLI, "Klubnichka Live (18+)", "Canlı yayın sağlayıcısı"),
        Source(HUB_SOURCE_TVDIZILER, "TVDiziler", "Yerli ve yabancı diziler"),
        Source(HUB_SOURCE_DDIZI, "DDizi", "Dizi kataloğu"),
        Source(HUB_SOURCE_DIZIBOXIZLE, "DiziBoxIzle", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_DIZIDAD, "DiziDad", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_DIZIPAL, "DiziPal", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_DIZIGECESI, "DiziGecesi", "Dizi kataloğu"),
        Source(HUB_SOURCE_DRAMADIZILERIM, "DramaDizilerim", "Kısa ve dikey diziler"),
        Source(HUB_SOURCE_FILMMODU, "FilmModu", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_JETFILMIZLE, "JetFilmIzle", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_SETFILMIZLE, "SetFilmIzle", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_SINEMACX, "SinemaCX", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_TIZAM, "TiZam", "Film ve dizi kataloğu"),
        Source(HUB_SOURCE_WEBDRAMATURKEY, "WebDramaTurkey", "Asya dizileri")
    )

    fun enabled(key: String): Boolean = getKey<Boolean>(key) ?: true

    fun showSettingsDialog(context: Context, onSave: () -> Unit) {
        val switches = linkedMapOf<String, Switch>()
        val sourceRows = linkedMapOf<String, View>()
        val statusLabels = linkedMapOf<String, TextView>()
        val dividerViews = linkedMapOf<String, View>()
        val filterButtons = linkedMapOf<String, Button>()
        var filterMode = "all"
        var refreshFilterAction: (() -> Unit)? = null
        val uiHandler = Handler(Looper.getMainLooper())
        val checkRunning = AtomicBoolean(false)
        val enabledSummary = TextView(context).apply {
            text = "● " + sources.count { enabled(it.key) } + " / " + sources.size + " kaynak açık"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(green)
            background = pill(Color.parseColor("#102B26"), Color.parseColor("#236A53"), dp(context, 16).toFloat())
            setPadding(dp(context, 11), dp(context, 7), dp(context, 11), dp(context, 7))
        }
        val rows = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 14), dp(context, 8), dp(context, 14), dp(context, 20))
            setBackgroundColor(backgroundColor)
        }

        rows.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 22), dp(context, 24), dp(context, 22), dp(context, 22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#14283C"), Color.parseColor("#21163F"), backgroundColor)
            ).apply { cornerRadius = dp(context, 20).toFloat() }

            addView(TextView(context).apply {
                text = "NEON HUB"
                textSize = 11f
                letterSpacing = 0.24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cyan)
            })
            addView(TextView(context).apply {
                text = "Kaynak kontrol merkezi"
                textSize = 23f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
                setPadding(0, dp(context, 7), 0, 0)
            })
            addView(TextView(context).apply {
                text = "Kaydet'e bastığında seçimler yeniden başlatma olmadan uygulanır."
                textSize = 13f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 7), 0, 0)
            })
            addView(enabledSummary, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 13) })
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 4)).apply {
                    topMargin = dp(context, 16)
                }
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cyan, purple)
                ).apply { cornerRadius = dp(context, 20).toFloat() }
            })
        })

        val sourceCountView = TextView(context).apply {
            text = sources.size.toString() + " kaynak"
            textSize = 11f
            setTextColor(cyan)
            background = pill(Color.parseColor("#102B3B"), Color.parseColor("#17536A"), dp(context, 30).toFloat())
            setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
        }
        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6), dp(context, 18), dp(context, 6), dp(context, 10))
            addView(TextView(context).apply {
                text = "KAYNAKLAR"
                textSize = 11f
                letterSpacing = 0.12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(sourceCountView)
        }
        rows.addView(heading)

        val searchBox = EditText(context).apply {
            hint = "Kaynak ara..."
            textSize = 14f
            setSingleLine(true)
            setTextColor(textPrimary)
            setHintTextColor(textSecondary)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            background = rounded(Color.parseColor("#111B2E"), dp(context, 13).toFloat(), borderColor)
            setPadding(dp(context, 15), dp(context, 3), dp(context, 15), dp(context, 3))
            contentDescription = "Kaynakları ada göre filtrele"
        }
        rows.addView(searchBox, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 48)
        ).apply { bottomMargin = dp(context, 10) })

        val filterBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(context, 12))
        }
        listOf("all" to "Tümü", "enabled" to "Açık", "disabled" to "Kapalı").forEach { (mode, label) ->
            val filterButton = Button(context).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondary)
                minimumHeight = 0
                setPadding(dp(context, 4), 0, dp(context, 4), 0)
                background = rounded(cardColor, dp(context, 10).toFloat(), borderColor)
                setOnClickListener {
                    filterMode = mode
                    refreshFilterAction?.invoke()
                }
            }
            filterButtons[mode] = filterButton
            filterBar.addView(filterButton, LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply {
                marginEnd = dp(context, 7)
            })
        }
        rows.addView(filterBar)

        val checkButtonRef = arrayOfNulls<Button>(1)
        val checkSummary = TextView(context).apply {
            text = "Kontrol, site adresinin yanıtını ölçer; video oynatmayı garanti etmez."
            textSize = 11f
            setTextColor(textSecondary)
            setPadding(dp(context, 2), dp(context, 5), dp(context, 2), dp(context, 11))
        }
        val checkButton = actionButton(context, "↻  Kaynakları Kontrol Et", cyan, Color.parseColor("#102B3B")) {
            val activeCheckButton = checkButtonRef[0] ?: return@actionButton
            if (!checkRunning.compareAndSet(false, true)) return@actionButton
            activeCheckButton.isEnabled = false
            activeCheckButton.text = "Kontrol ediliyor..."
            checkSummary.text = "Site adresleri arka planda kontrol ediliyor. Kaynaklar otomatik kapatılmaz."
            statusLabels.values.forEach {
                it.text = "● Kontrol ediliyor..."
                it.setTextColor(textSecondary)
            }

            val remaining = AtomicInteger(sources.size)
            val goodCount = AtomicInteger(0)
            val warningCount = AtomicInteger(0)
            val failedCount = AtomicInteger(0)
            val executor = Executors.newFixedThreadPool(4)
            sources.forEach { source ->
                executor.execute {
                    val result = probeSource(source.key)
                    when (result.category) {
                        0 -> goodCount.incrementAndGet()
                        1 -> warningCount.incrementAndGet()
                        else -> failedCount.incrementAndGet()
                    }
                    uiHandler.post {
                        statusLabels[source.key]?.apply {
                            text = "● " + result.label
                            setTextColor(result.color)
                        }
                        if (remaining.decrementAndGet() == 0) {
                            checkRunning.set(false)
                            activeCheckButton.isEnabled = true
                            activeCheckButton.text = "↻  Kaynakları Yeniden Kontrol Et"
                            checkSummary.text = "Tamamlandı: " + goodCount.get() + " erişilebilir · " +
                                warningCount.get() + " uyarı · " + failedCount.get() +
                                " yanıt yok. Bu sonuç video oynatmayı doğrulamaz."
                        }
                    }
                }
            }
            executor.shutdown()
        }
        checkButtonRef[0] = checkButton
        rows.addView(checkButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 44)
        ).apply { bottomMargin = dp(context, 2) })
        rows.addView(checkSummary)

        val sourceCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, dp(context, 16).toFloat(), borderColor)
        }

        sources.forEachIndexed { index, source ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 16), dp(context, 12), dp(context, 12), dp(context, 12))
                isClickable = true
                isFocusable = true
                background = pressable(context)
            }

            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            copy.addView(TextView(context).apply {
                text = source.title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            copy.addView(TextView(context).apply {
                text = source.detail
                textSize = 11f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 3), 0, 0)
            })
            val statusLabel = TextView(context).apply {
                text = "● Henüz kontrol edilmedi"
                textSize = 10f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 4), 0, 0)
            }
            statusLabels[source.key] = statusLabel
            copy.addView(statusLabel)
            row.addView(copy)

            val toggle = Switch(context).apply {
                isChecked = enabled(source.key)
                contentDescription = "Etkinleştir: ${source.title}"
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#CBD5E1"))
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(green, Color.parseColor("#3A455B"))
                )
            }
            switches[source.key] = toggle
            toggle.setOnCheckedChangeListener { _, _ -> refreshFilterAction?.invoke() }
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            sourceRows[source.key] = row
            sourceCard.addView(row)

            if (index != sources.lastIndex) {
                val divider = View(context).apply {
                    setBackgroundColor(borderColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
                    ).apply {
                        marginStart = dp(context, 16)
                        marginEnd = dp(context, 16)
                    }
                }
                dividerViews[source.key] = divider
                sourceCard.addView(divider)
            }
        }
        rows.addView(sourceCard)

        fun refreshSourceFilter() {
            val query = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
            var visibleCount = 0
            sources.forEach { source ->
                val matchesQuery = query.isBlank() ||
                    source.title.lowercase().contains(query) ||
                    source.detail.lowercase().contains(query)
                val isEnabled = switches[source.key]?.isChecked == true
                val matchesMode = when (filterMode) {
                    "enabled" -> isEnabled
                    "disabled" -> !isEnabled
                    else -> true
                }
                val visible = matchesQuery && matchesMode
                sourceRows[source.key]?.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) visibleCount++
            }

            sources.forEachIndexed { index, source ->
                val currentVisible = sourceRows[source.key]?.visibility == View.VISIBLE
                val laterVisible = sources.drop(index + 1).any {
                    sourceRows[it.key]?.visibility == View.VISIBLE
                }
                dividerViews[source.key]?.visibility =
                    if (currentVisible && laterVisible) View.VISIBLE else View.GONE
            }

            sourceCountView.text = visibleCount.toString() + " kaynak"
            enabledSummary.text = "● " + switches.values.count { it.isChecked } +
                " / " + sources.size + " kaynak açık"
            filterButtons.forEach { (mode, button) ->
                val selected = mode == filterMode
                button.setTextColor(if (selected) cyan else textSecondary)
                button.background = rounded(
                    if (selected) Color.parseColor("#103347") else cardColor,
                    dp(context, 10).toFloat(),
                    if (selected) cyan else borderColor
                )
            }
        }
        refreshFilterAction = { refreshSourceFilter() }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshSourceFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshSourceFilter()

        val quickActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(context, 14), 0, dp(context, 8))
        }
        quickActions.addView(actionButton(context, "✓  Tümünü Aç", green, Color.parseColor("#102B26")) {
            switches.values.forEach { it.isChecked = true }
        }, LinearLayout.LayoutParams(0, dp(context, 42), 1f))
        quickActions.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 10), 1)
        })
        quickActions.addView(actionButton(context, "×  Tümünü Kapat", Color.parseColor("#FDA4AF"), Color.parseColor("#321923")) {
            switches.values.forEach { it.isChecked = false }
        }, LinearLayout.LayoutParams(0, dp(context, 42), 1f))
        rows.addView(quickActions)

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            isScrollbarFadingEnabled = true
            setBackgroundColor(backgroundColor)
            addView(rows)
        }

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .setPositiveButton("Kaydet") { _, _ ->
                sources.forEach { source ->
                    setKey(source.key, switches[source.key]?.isChecked ?: true)
                }
                // Apply the source switches through the single NeonHub provider without restarting.
                onSave()
                Toast.makeText(context, "NeonHub ayarları hemen uygulandı", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Vazgeç", null)
            .create()

        dialog.window?.setBackgroundDrawable(rounded(backgroundColor, dp(context, 20).toFloat()))
        dialog.show()
        dialog.window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (context.resources.displayMetrics.heightPixels * 0.82f).toInt()
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(cyan)
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(textSecondary)
            isAllCaps = false
        }
    }

    private fun probeSource(key: String): SourceProbe {
        val address = NeonHubController.source(key)?.mainUrl?.trim()
            ?: return SourceProbe("Kaynak başlatılamadı", red, 2)
        if (!address.startsWith("https://", ignoreCase = true) &&
            !address.startsWith("http://", ignoreCase = true)
        ) {
            return SourceProbe("Geçerli web adresi yok", red, 2)
        }

        var connection: HttpURLConnection? = null
        return try {
            val opened = URL(address).openConnection()
            if (opened !is HttpURLConnection) {
                return SourceProbe("Desteklenmeyen bağlantı", red, 2)
            }
            connection = opened
            opened.instanceFollowRedirects = true
            opened.requestMethod = "GET"
            opened.connectTimeout = 4500
            opened.readTimeout = 4500
            opened.useCaches = false
            opened.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
            )
            opened.setRequestProperty("Range", "bytes=0-0")

            val code = opened.responseCode
            when (code) {
                in 200..399 -> SourceProbe("Ulaşılabilir · HTTP " + code, green, 0)
                in 400..499 -> SourceProbe("Erişiyor, kısıtlı · HTTP " + code, amber, 1)
                else -> SourceProbe("Sunucu hatası · HTTP " + code, red, 2)
            }
        } catch (_: java.net.SocketTimeoutException) {
            SourceProbe("Zaman aşımı", amber, 2)
        } catch (_: Exception) {
            SourceProbe("Bağlantı kurulamadı", red, 2)
        } finally {
            connection?.disconnect()
        }
    }

    private fun actionButton(
        context: Context,
        label: String,
        foreground: Int,
        backgroundColor: Int,
        action: () -> Unit
    ): Button = Button(context).apply {
        text = label
        textSize = 11f
        setTextColor(foreground)
        isAllCaps = false
        setTypeface(null, Typeface.BOLD)
        background = rounded(backgroundColor, dp(context, 12).toFloat(), foreground)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            if (stroke != null) setStroke(1, stroke)
        }

    private fun pill(color: Int, stroke: Int, radius: Float) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
            setStroke(1, stroke)
        }

    private fun pressable(context: Context) =
        rounded(Color.parseColor("#182236"), dp(context, 12).toFloat())

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
