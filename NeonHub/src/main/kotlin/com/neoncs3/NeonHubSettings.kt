package com.neoncs3

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

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
    private val borderColor = Color.parseColor("#26334D")

    data class Source(val key: String, val title: String, val detail: String)

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
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 48), dp(context, 4)).apply {
                    topMargin = dp(context, 16)
                }
                background = GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(cyan, purple)
                ).apply { cornerRadius = dp(context, 20).toFloat() }
            })
        })

        val heading = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 6), dp(context, 18), dp(context, 6), dp(context, 10))
            addView(TextView(context).apply {
                text = "EKLENTİLER"
                textSize = 11f
                letterSpacing = 0.12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textSecondary)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = "${sources.size} kaynak"
                textSize = 11f
                setTextColor(cyan)
                background = pill(Color.parseColor("#102B3B"), Color.parseColor("#17536A"), dp(context, 30).toFloat())
                setPadding(dp(context, 10), dp(context, 5), dp(context, 10), dp(context, 5))
            })
        }
        rows.addView(heading)

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
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            sourceCard.addView(row)

            if (index != sources.lastIndex) {
                sourceCard.addView(View(context).apply {
                    setBackgroundColor(borderColor)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 1)
                    ).apply {
                        marginStart = dp(context, 16)
                        marginEnd = dp(context, 16)
                    }
                })
            }
        }
        rows.addView(sourceCard)

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
                // Save and apply immediately; the controller adds/removes APIs in-place.
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
