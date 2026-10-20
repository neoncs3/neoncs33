package com.neoncs3

import android.app.Dialog
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
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

    private val backgroundColor = Color.parseColor("#100B18")
    private val cardColor = Color.parseColor("#1A1427")
    private val raisedCardColor = Color.parseColor("#241B35")
    private val textPrimary = Color.parseColor("#FFF9FE")
    private val textSecondary = Color.parseColor("#B8AFC9")
    private val cyan = Color.parseColor("#FF76C8")
    private val purple = Color.parseColor("#B39CFF")
    private val green = Color.parseColor("#D5FF80")
    private val amber = Color.parseColor("#FFC979")
    private val red = Color.parseColor("#FF6B8A")
    private val borderColor = Color.parseColor("#493657")

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

    fun showSettingsDialog(context: Context, pluginResources: Resources?, onSave: () -> Unit) {
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
        val switches = linkedMapOf<String, Switch>()
        val sourceRows = linkedMapOf<String, View>()
        val statusLabels = linkedMapOf<String, TextView>()
        val filterButtons = linkedMapOf<String, TextView>()
        var filterMode = "all"
        var refreshFilterAction: (() -> Unit)? = null
        val uiHandler = Handler(Looper.getMainLooper())
        val checkRunning = AtomicBoolean(false)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        // Branded top bar: this is a full-screen control panel, not a stock alert dialog.
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), dp(context, 18), dp(context, 16), dp(context, 15))
            setBackgroundColor(backgroundColor)
        }
        topBar.addView(brandMark(context), LinearLayout.LayoutParams(
            dp(context, 52), dp(context, 52)
        ).apply { marginEnd = dp(context, 13) })
        val brandCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brandCopy.addView(TextView(context).apply {
            text = "NEONHUB"
            textSize = 21f
            letterSpacing = 0.035f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        brandCopy.addView(TextView(context).apply {
            text = "KAYNAK YÖNETİM PANELİ"
            textSize = 9f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            setPadding(0, dp(context, 3), 0, 0)
        })
        topBar.addView(brandCopy)
        val closeButton = TextView(context).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            background = rounded(raisedCardColor, dp(context, 16).toFloat(), borderColor)
            isClickable = true
            isFocusable = true
            contentDescription = "Ayarları kapat"
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(closeButton, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        root.addView(topBar)
        root.addView(View(context).apply {
            setBackgroundColor(borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 22))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val initialEnabledCount = sources.count { enabled(it.key) }
        val hero = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 19), dp(context, 20), dp(context, 19), dp(context, 18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#3D1F48"), Color.parseColor("#2B214D"), Color.parseColor("#1B152B"))
            ).apply {
                cornerRadius = dp(context, 22).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#594064"))
            }
        }
        val heroEyebrow = TextView(context).apply {
            text = "KİŞİSELLEŞTİRİLEBİLİR İÇERİK"
            textSize = 9f
            letterSpacing = 0.14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
        }
        hero.addView(heroEyebrow)
        hero.addView(TextView(context).apply {
            text = "Bütün kaynakların,\ntek bir merkezde."
            textSize = 25f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(0, dp(context, 9), 0, 0)
        })
        hero.addView(TextView(context).apply {
            text = "İstediğin siteleri etkinleştir, diğerlerini kapat. Seçimlerin Kaydet ile uygulanır."
            textSize = 12f
            setTextColor(textSecondary)
            setLineSpacing(dp(context, 2).toFloat(), 1f)
            setPadding(0, dp(context, 8), 0, dp(context, 16))
        })
        val openCount = TextView(context).apply {
            text = initialEnabledCount.toString()
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(green)
        }
        val totalCount = TextView(context).apply {
            text = sources.size.toString()
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        }
        val closedCount = TextView(context).apply {
            text = (sources.size - initialEnabledCount).toString()
            textSize = 23f
            setTypeface(null, Typeface.BOLD)
            setTextColor(amber)
        }
        fun metric(label: String, number: TextView, tint: Int): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), dp(context, 11), dp(context, 8), dp(context, 10))
                background = rounded(Color.argb(28, 255, 255, 255), dp(context, 14).toFloat(), Color.argb(42, 255, 255, 255))
                addView(number)
                addView(TextView(context).apply {
                    text = label
                    textSize = 10f
                    setTextColor(tint)
                    setPadding(0, dp(context, 2), 0, 0)
                })
            }
        }
        val metrics = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        metrics.addView(metric("AÇIK", openCount, green), LinearLayout.LayoutParams(0, dp(context, 66), 1f).apply {
            marginEnd = dp(context, 8)
        })
        metrics.addView(metric("TOPLAM", totalCount, textSecondary), LinearLayout.LayoutParams(0, dp(context, 66), 1f).apply {
            marginEnd = dp(context, 8)
        })
        metrics.addView(metric("KAPALI", closedCount, amber), LinearLayout.LayoutParams(0, dp(context, 66), 1f))
        hero.addView(metrics)
        content.addView(hero)

        val sourceHeading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 2), dp(context, 23), dp(context, 2), dp(context, 11))
        }
        val sourceHeadingCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        sourceHeadingCopy.addView(TextView(context).apply {
            text = "Kaynakların"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        sourceHeadingCopy.addView(TextView(context).apply {
            text = "Listeyi düzenle ve durumlarını kontrol et"
            textSize = 11f
            setTextColor(textSecondary)
            setPadding(0, dp(context, 3), 0, 0)
        })
        sourceHeading.addView(sourceHeadingCopy)
        val sourceCountView = TextView(context).apply {
            text = sources.size.toString() + " / " + sources.size
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            background = pill(Color.parseColor("#321C3D"), Color.parseColor("#89518C"), dp(context, 22).toFloat())
            setPadding(dp(context, 10), dp(context, 7), dp(context, 10), dp(context, 7))
        }
        sourceHeading.addView(sourceCountView)
        content.addView(sourceHeading)

        val filterBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 11), 0, dp(context, 13))
        }
        listOf("all" to "Tümü", "enabled" to "● Açık", "disabled" to "○ Kapalı").forEach { (mode, label) ->
            val chip = TextView(context).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(context, 12), 0, dp(context, 12), 0)
                setOnClickListener {
                    filterMode = mode
                    refreshFilterAction?.invoke()
                }
            }
            filterButtons[mode] = chip
            filterBar.addView(chip, LinearLayout.LayoutParams(0, dp(context, 36), 1f).apply {
                marginEnd = dp(context, 7)
            })
        }
        content.addView(filterBar)

        val checkSummary = TextView(context).apply {
            text = "Durum kontrolü, site yanıtını ölçer; gerçek video oynatmayı garanti etmez."
            textSize = 10f
            setTextColor(textSecondary)
            setPadding(dp(context, 2), dp(context, 7), dp(context, 2), 0)
        }
        val checkButtonRef = arrayOfNulls<Button>(1)
        val healthCard = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 13), dp(context, 12), dp(context, 12), dp(context, 12))
            background = rounded(Color.parseColor("#21172D"), dp(context, 16).toFloat(), Color.parseColor("#594064"))
        }
        val healthCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        healthCopy.addView(TextView(context).apply {
            text = "Kaynak sağlık kontrolü"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        healthCopy.addView(TextView(context).apply {
            text = "Sitelerin yanıt verip vermediğini ölç"
            textSize = 10f
            setTextColor(textSecondary)
            setPadding(0, dp(context, 3), 0, 0)
        })
        healthCard.addView(healthCopy)
        val checkButton = actionButton(context, "Kontrol Et  ↗", cyan, Color.parseColor("#372142")) {
            val activeButton = checkButtonRef[0] ?: return@actionButton
            if (!checkRunning.compareAndSet(false, true)) return@actionButton
            activeButton.isEnabled = false
            activeButton.text = "Kontrol..."
            checkSummary.text = "${sources.size} kaynak kontrol ediliyor..."
            statusLabels.values.forEach {
                it.text = "● Kontrol ediliyor"
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
                            activeButton.isEnabled = true
                            activeButton.text = "Yeniden Dene"
                            checkSummary.text = "Sonuç: " + goodCount.get() + " erişilebilir · " +
                                warningCount.get() + " uyarı · " + failedCount.get() +
                                " yanıt yok. Kaynaklar otomatik kapatılmadı."
                        }
                    }
                }
            }
            executor.shutdown()
        }
        checkButtonRef[0] = checkButton
        healthCard.addView(checkButton, LinearLayout.LayoutParams(
            dp(context, 103), dp(context, 40)
        ))
        content.addView(healthCard)
        content.addView(checkSummary)

        val sectionCaption = TextView(context).apply {
            text = "AKTİF KAYNAK LİSTESİ"
            textSize = 9f
            letterSpacing = 0.15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textSecondary)
            setPadding(dp(context, 2), dp(context, 22), dp(context, 2), dp(context, 10))
        }
        content.addView(sectionCaption)

        val sourceContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(sourceContainer)

        sources.forEachIndexed { index, source ->
            val accent = when (index % 4) {
                0 -> cyan
                1 -> purple
                2 -> green
                else -> amber
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 12), dp(context, 10), dp(context, 12))
                background = rounded(cardColor, dp(context, 16).toFloat(), borderColor)
                isClickable = true
                isFocusable = true
            }
            row.addView(sourceIconTile(context, source, accent), LinearLayout.LayoutParams(
                dp(context, 43), dp(context, 43)
            ).apply { marginEnd = dp(context, 12) })

            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            copy.addView(TextView(context).apply {
                text = source.title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            copy.addView(TextView(context).apply {
                text = source.detail
                textSize = 10f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 2), 0, 0)
            })
            val statusLabel = TextView(context).apply {
                text = "● Henüz kontrol edilmedi"
                textSize = 9f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 5), 0, 0)
            }
            statusLabels[source.key] = statusLabel
            copy.addView(statusLabel)
            row.addView(copy)

            val toggle = Switch(context).apply {
                isChecked = enabled(source.key)
                contentDescription = "Etkinleştir: " + source.title
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#D8D0E2"))
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(green, Color.parseColor("#55485D"))
                )
                showText = false
            }
            switches[source.key] = toggle
            toggle.setOnCheckedChangeListener { _, _ -> refreshFilterAction?.invoke() }
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            sourceRows[source.key] = row
            sourceContainer.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 9) })
        }

        fun updateMetricsAndFilters() {
            val enabledNow = switches.values.count { it.isChecked }
            openCount.text = enabledNow.toString()
            totalCount.text = sources.size.toString()
            closedCount.text = (sources.size - enabledNow).toString()

            var visibleCount = 0
            sources.forEach { source ->
                val isEnabled = switches[source.key]?.isChecked == true
                val matchesMode = when (filterMode) {
                    "enabled" -> isEnabled
                    "disabled" -> !isEnabled
                    else -> true
                }
                val visible = matchesMode
                sourceRows[source.key]?.visibility = if (visible) View.VISIBLE else View.GONE
                if (visible) visibleCount++
            }
            sourceCountView.text = visibleCount.toString() + " / " + sources.size
            filterButtons.forEach { (mode, chip) ->
                val selected = mode == filterMode
                chip.setTextColor(if (selected) Color.parseColor("#29152B") else textSecondary)
                chip.background = rounded(
                    if (selected) cyan else raisedCardColor,
                    dp(context, 18).toFloat(),
                    if (selected) cyan else borderColor
                )
            }
        }
        refreshFilterAction = { updateMetricsAndFilters() }
        updateMetricsAndFilters()

        // Fixed bottom action shelf stays visible while the source list scrolls.
        root.addView(View(context).apply {
            setBackgroundColor(borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 15))
            setBackgroundColor(Color.parseColor("#0D0914"))
        }
        val bulkActions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bulkActions.addView(actionButton(context, "✓  Tümünü Aç", green, Color.parseColor("#26391F")) {
            switches.values.forEach { it.isChecked = true }
        }, LinearLayout.LayoutParams(0, dp(context, 38), 1f).apply {
            marginEnd = dp(context, 8)
        })
        bulkActions.addView(actionButton(context, "×  Tümünü Kapat", red, Color.parseColor("#42172F")) {
            switches.values.forEach { it.isChecked = false }
        }, LinearLayout.LayoutParams(0, dp(context, 38), 1f))
        footer.addView(bulkActions)
        footer.addView(actionButton(
            context,
            "↺  Varsayılanları Yükle · Tüm Kaynakları Aç",
            cyan,
            Color.parseColor("#321C3D")
        ) {
            switches.values.forEach { it.isChecked = true }
            refreshFilterAction?.invoke()
            Toast.makeText(
                context,
                "Varsayılan seçimler hazır. Kalıcı olması için Kaydet ve Uygula'ya bas.",
                Toast.LENGTH_LONG
            ).show()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 38)
        ).apply { topMargin = dp(context, 8) })
        val bottomButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(context, 9), 0, 0)
        }
        bottomButtons.addView(actionButton(context, "Vazgeç", textSecondary, raisedCardColor) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(context, 48), 0.8f).apply {
            marginEnd = dp(context, 9)
        })
        bottomButtons.addView(actionButton(context, "Kaydet ve Uygula  →", Color.parseColor("#29152B"), cyan) {
            sources.forEach { source ->
                setKey(source.key, switches[source.key]?.isChecked ?: true)
            }
            onSave()
            Toast.makeText(context, "NeonHub ayarları uygulandı", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(context, 48), 1.4f))
        footer.addView(bottomButtons)
        root.addView(footer)

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(backgroundColor))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.72f }
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            statusBarColor = backgroundColor
            navigationBarColor = Color.parseColor("#0D0914")
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    /**
     * Minimal, dependable settings fallback if the themed full-screen dialog fails
     * on a particular CloudStream build or Android TV device.
     */
    fun showFallbackSettingsDialog(context: Context, onSave: () -> Unit) {
        // Keep the branded full-screen NeonHub appearance even when the richer
        // settings panel fails on a particular CloudStream/Android build.
        val switches = linkedMapOf<String, Switch>()
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
        }

        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 20), dp(context, 18), dp(context, 16), dp(context, 15))
            setBackgroundColor(backgroundColor)
        }
        topBar.addView(brandMark(context), LinearLayout.LayoutParams(
            dp(context, 52), dp(context, 52)
        ).apply { marginEnd = dp(context, 13) })
        val brandCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        brandCopy.addView(TextView(context).apply {
            text = "NEONHUB"
            textSize = 21f
            letterSpacing = 0.035f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        brandCopy.addView(TextView(context).apply {
            text = "KAYNAK YÖNETİM PANELİ"
            textSize = 9f
            letterSpacing = 0.12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            setPadding(0, dp(context, 3), 0, 0)
        })
        topBar.addView(brandCopy)

        val closeButton = TextView(context).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(textSecondary)
            background = rounded(raisedCardColor, dp(context, 16).toFloat(), borderColor)
            isClickable = true
            isFocusable = true
            contentDescription = "Ayarları kapat"
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(closeButton, LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)))
        root.addView(topBar)
        root.addView(View(context).apply {
            setBackgroundColor(borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
        })

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 20))
        }
        content.addView(TextView(context).apply {
            text = "Kaynakların"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
        })
        content.addView(TextView(context).apply {
            text = "Kullanmak istediğin siteleri etkinleştir. Seçimlerin Kaydet ile uygulanır."
            textSize = 12f
            setTextColor(textSecondary)
            setPadding(0, dp(context, 5), 0, dp(context, 16))
        })

        sources.forEachIndexed { index, source ->
            val accent = when (index % 4) {
                0 -> cyan
                1 -> purple
                2 -> green
                else -> amber
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 12), dp(context, 10), dp(context, 12))
                background = rounded(cardColor, dp(context, 16).toFloat(), borderColor)
                isClickable = true
                isFocusable = true
            }
            row.addView(sourceIconTile(context, source, accent), LinearLayout.LayoutParams(
                dp(context, 43), dp(context, 43)
            ).apply { marginEnd = dp(context, 12) })
            val copy = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            copy.addView(TextView(context).apply {
                text = source.title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textPrimary)
            })
            copy.addView(TextView(context).apply {
                text = source.detail
                textSize = 10f
                setTextColor(textSecondary)
                setPadding(0, dp(context, 2), 0, 0)
            })
            row.addView(copy)

            val toggle = Switch(context).apply {
                isChecked = enabled(source.key)
                contentDescription = "Etkinleştir: " + source.title
                thumbTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(Color.WHITE, Color.parseColor("#D8D0E2"))
                )
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(green, Color.parseColor("#55485D"))
                )
                showText = false
            }
            switches[source.key] = toggle
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            content.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(context, 9) })
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = false
            isScrollbarFadingEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        root.addView(View(context).apply {
            setBackgroundColor(borderColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
            )
        })
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 18), dp(context, 11), dp(context, 18), dp(context, 15))
            setBackgroundColor(Color.parseColor("#0D0914"))
        }
        footer.addView(actionButton(
            context,
            "↺  Varsayılanları Yükle · Tüm Kaynakları Aç",
            cyan,
            Color.parseColor("#321C3D")
        ) {
            switches.values.forEach { it.isChecked = true }
            Toast.makeText(
                context,
                "Varsayılan seçimler hazır. Kalıcı olması için Kaydet ve Uygula'ya bas.",
                Toast.LENGTH_LONG
            ).show()
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 38)
        ).apply { bottomMargin = dp(context, 9) })

        val footerButtons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        footerButtons.addView(actionButton(context, "Vazgeç", textSecondary, raisedCardColor) {
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(context, 48), 0.8f).apply {
            marginEnd = dp(context, 9)
        })
        footerButtons.addView(actionButton(context, "Kaydet ve Uygula  →", Color.parseColor("#29152B"), cyan) {
            sources.forEach { source ->
                setKey(source.key, switches[source.key]?.isChecked ?: true)
            }
            onSave()
            Toast.makeText(context, "NeonHub ayarları uygulandı", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }, LinearLayout.LayoutParams(0, dp(context, 48), 1.4f))
        footer.addView(footerButtons)
        root.addView(footer)

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(backgroundColor))
            setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.72f }
            statusBarColor = backgroundColor
            navigationBarColor = Color.parseColor("#0D0914")
        }
        dialog.show()
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    private fun brandMark(context: Context): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = "NeonHub logosu"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(purple, cyan, red)
            ).apply {
                cornerRadius = dp(context, 17).toFloat()
                setStroke(dp(context, 1), Color.argb(175, 255, 255, 255))
            }
            addView(TextView(context).apply {
                text = "N"
                textSize = 27f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                includeFontPadding = false
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ))
        }

    private fun sourceIcon(source: Source): String = when (source.key) {
        HUB_SOURCE_ASYAFILM -> "AS"
        HUB_SOURCE_DIZIMAG -> "DM"
        HUB_SOURCE_HDFILMDIZI -> "HD"
        HUB_SOURCE_CANLI -> "18+"
        HUB_SOURCE_TVDIZILER -> "TV"
        HUB_SOURCE_DDIZI -> "DD"
        HUB_SOURCE_DIZIBOXIZLE -> "DB"
        HUB_SOURCE_DIZIDAD -> "DAD"
        HUB_SOURCE_DIZIPAL -> "DP"
        HUB_SOURCE_DIZIGECESI -> "DG"
        HUB_SOURCE_DRAMADIZILERIM -> "DR"
        HUB_SOURCE_FILMMODU -> "FM"
        HUB_SOURCE_JETFILMIZLE -> "JF"
        HUB_SOURCE_SETFILMIZLE -> "SF"
        HUB_SOURCE_SINEMACX -> "SC"
        HUB_SOURCE_TIZAM -> "TZ"
        HUB_SOURCE_WEBDRAMATURKEY -> "WD"
        else -> source.title.take(2).uppercase()
    }

    private fun sourceIconTile(context: Context, source: Source, accent: Int): TextView {
        val endColor = when (accent) {
            cyan -> red
            purple -> cyan
            green -> purple
            amber -> red
            else -> purple
        }
        val glyph = sourceIcon(source)
        return TextView(context).apply {
            text = glyph
            textSize = if (glyph.length > 2) 10f else 12f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            letterSpacing = 0.015f
            includeFontPadding = false
            contentDescription = source.title + " simgesi"
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(accent, endColor)
            ).apply {
                cornerRadius = dp(context, 13).toFloat()
                setStroke(dp(context, 1), Color.argb(170, 255, 255, 255))
            }
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
        rounded(Color.parseColor("#2B2038"), dp(context, 12).toFloat())

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
