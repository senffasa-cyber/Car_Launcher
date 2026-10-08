package com.arena.carlauncher.ui.card

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import com.arena.carlauncher.R
import com.arena.carlauncher.data.Cards
import com.arena.carlauncher.data.MediaSnapshot
import com.arena.carlauncher.media.MediaHub
import com.arena.carlauncher.theme.Palette
import com.arena.carlauncher.ui.Views
import com.arena.carlauncher.util.Format
import com.arena.carlauncher.util.ImageCache
import kotlin.math.roundToInt

/**
 * The centrepiece: whatever is playing, in the middle of the screen, controlled with three big
 * targets that a driver can hit without looking.
 *
 * Progress is interpolated in [MediaSeekBar] between the 1 Hz samples that come from the media
 * session, so the bar glides at frame rate while the launcher does one IPC read per second instead
 * of 60 — the difference between a smooth car UI and aUnresponsive one.
 */
class MusicCard(context: Context, full: Boolean) : BaseCard(context, Cards.MUSIC, full) {

    override val iconRes = R.drawable.ic_music

    private val cover = AppCompatImageView(context)
    private val titleView = Views.marquee(context, "", if (full) 21f else 14f)
    private val artistView = Views.marquee(context, "", if (full) 14.5f else 11f, Palette.colors.onSurfaceMuted, bold = false)
    private val sourceChip = Views.chipLabel(context, "")
    private val seek = MediaSeekBar(context, full)
    private val elapsed = Views.text(context, "0:00", if (full) 11.5f else 9.5f, Palette.colors.onSurfaceMuted, condensed = true)
    private val remaining = Views.text(context, "0:00", if (full) 11.5f else 9.5f, Palette.colors.onSurfaceMuted, condensed = true)
    private val albumView = Views.marquee(context, "", if (full) 10.5f else 9f, Palette.colors.onSurfaceMuted, bold = false)
    private val playButton = Views.accentButton(context, R.drawable.ic_play, if (full) 62 else 36) { MediaHub.act(MediaHub.Action.PLAY_PAUSE) }
    private val volumeView = Views.text(context, "", if (full) 11f else 9f, Palette.colors.onSurfaceMuted, condensed = true)

    private var state = MediaSnapshot.EMPTY
    private var lastCoverKey = ""

    override fun onCreate() {
        if (full) buildFull() else buildMini()
    }

    private fun buildFull() {
        body.addView(header(context.getString(R.string.card_music), sourceChip))

        val main = Views.row(context, paddingDp = 0)
        cover.layoutParams = LinearLayout.LayoutParams(Views.dp(context, 132f), Views.dp(context, 132f))
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = Palette.fill(context, Palette.colors.surfaceRaised, 16f)
        main.addView(cover)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Views.dp(context, 14f), 0, 0, 0)
        }
        texts.addView(titleView, weightWrap())
        texts.addView(artistView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(context, 3f) })
        texts.addView(
            albumView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = Views.dp(context, 2f) }
        )
        main.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        body.addView(main, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = Views.dp(context, 10f) })

        // progress
        val timesRow = Views.row(context)
        timesRow.addView(elapsed, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        timesRow.addView(Views.weightSpacer(context), LinearLayout.LayoutParams(0, 1, 1f))
        timesRow.addView(remaining, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val progressCol = Views.column(context, paddingDp = 0)
        progressCol.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Views.dp(context, 26f)
        ))
        progressCol.addView(timesRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        body.addView(progressCol, matchWrap())

        // transport
        val buttons = Views.row(context).apply { gravity = Gravity.CENTER }
        val small = if (full) 46 else 34
        buttons.addView(Views.iconButton(context, R.drawable.ic_prev, small, if (full) 24 else 18) { MediaHub.act(MediaHub.Action.PREV) })
        buttons.addView(Views.weightSpacer(context), LinearLayout.LayoutParams(0, 1, 0.25f))
        buttons.addView(playButton, LinearLayout.LayoutParams(Views.dp(context, if (full) 62f else 36f), Views.dp(context, if (full) 62f else 36f)))
        buttons.addView(Views.weightSpacer(context), LinearLayout.LayoutParams(0, 1, 0.25f))
        buttons.addView(Views.iconButton(context, R.drawable.ic_next, small, if (full) 24 else 18) { MediaHub.act(MediaHub.Action.NEXT) })
        body.addView(buttons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(context, 6f) })

        // footer: volume + open player + long-press source picker
        val footer = Views.row(context)
        footer.addView(
            Views.iconButton(context, R.drawable.ic_volume_down, 34, 17, Palette.colors.onSurfaceMuted, background = false) {
                MediaHub.adjustVolume(context, false)
            }
        )
        footer.addView(
            Views.iconButton(context, R.drawable.ic_volume_up, 34, 17, Palette.colors.onSurfaceMuted, background = false) {
                MediaHub.adjustVolume(context, true)
            }
        )
        footer.addView(volumeView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = Views.dp(context, 4f) })
        footer.addView(Views.weightSpacer(context))
        footer.addView(
            Views.iconButton(context, R.drawable.ic_open_in_app, 36, 19) { MediaHub.openPlayer(context) }
        )
        body.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = Views.dp(context, 2f) })

        seek.onSeek = { fraction ->
            if (state.durationMs > 0) MediaHub.seekTo((state.durationMs * fraction).toLong())
        }
        cover.setOnClickListener { MediaHub.openPlayer(context) }
        cover.setOnLongClickListener {
            MediaHub.setPreferredPackage("")
            MediaHub.fetchSessions(context, force = true)
            true
        }
    }

    private fun buildMini() {
        val row = Views.row(context, paddingDp = 0)
        cover.layoutParams = LinearLayout.LayoutParams(Views.dp(context, 46f), Views.dp(context, 46f))
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        cover.background = Palette.fill(context, Palette.colors.surfaceRaised, 12f)
        row.addView(cover)

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Views.dp(context, 9f), 0, 0, 0)
        }
        texts.addView(titleView, weightWrap())
        texts.addView(artistView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            Views.iconButton(context, R.drawable.ic_play, 34, 17) { MediaHub.act(MediaHub.Action.PLAY_PAUSE) },
            LinearLayout.LayoutParams(Views.dp(context, 34f), Views.dp(context, 34f)).apply {
                marginStart = Views.dp(context, 6f)
            }
        )
        body.addView(row, matchWrap())
        body.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, Views.dp(context, 10f)
        ).apply { topMargin = Views.dp(context, 8f) })
        seek.compact = true
        seek.setOnClickListener { MediaHub.act(MediaHub.Action.PLAY_PAUSE) }
    }

    override fun onBind() {
        observe(MediaHub.state) { render(it) }
        render(MediaHub.state.value)
    }

    @SuppressLint("SetTextI18n")
    private fun render(s: MediaSnapshot) {
        state = s
        titleView.text = Format.localized(context, if (s.hasText) s.title else context.getString(R.string.music_nothing_playing))
        artistView.text = if (s.artist.isNotBlank()) Format.localized(context, s.artist)
        else context.getString(R.string.music_paused_hint)
        sourceChip.text = when {
            s.ownerLabel.isNotBlank() -> s.ownerLabel.substringBefore(' ').take(14)
            s.source == MediaSnapshot.SOURCE_NOTIFICATION -> context.getString(R.string.music_from_notification)
            else -> ""
        }
        sourceChip.visibility = if (sourceChip.text.isNullOrBlank()) View.GONE else View.VISIBLE
        albumView.text = when {
            s.album.isNotBlank() -> Format.localized(context, s.album)
            else -> ""
        }
        albumView.visibility = if (albumView.text.isNullOrBlank()) View.GONE else View.VISIBLE
        playButton.setImageResource(if (s.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        seek.setProgress(
            if (s.durationMs > 0) s.positionNow().toFloat() / s.durationMs.toFloat() else 0f,
            s.durationMs > 0 && s.canSeek
        )
        if (!s.isPlaying) seek.releaseScrub()
        elapsed.text = Format.localized(context, Format.clock(s.positionNow().coerceAtLeast(0L)))
        remaining.text = if (s.remaining.isBlank()) "" else "-" + Format.localized(context, s.remaining)
        val vol = MediaHub.volumePercent(context)
        volumeView.text = if (vol < 0) "" else Format.localized(context, "$vol%")
        setCover(s.title, s.artist, s.artwork)
        if (full && !s.hasText) {
            titleView.text = context.getString(R.string.music_empty_title)
            artistView.text = context.getString(R.string.music_empty_hint)
        }
    }

    private fun setCover(title: String, artist: String, art: Bitmap?) {
        val key = "$title|$artist"
        if (key == lastCoverKey) return
        lastCoverKey = key
        if (art == null || art.isRecycled) {
            cover.setImageResource(R.drawable.ic_music)
            cover.scaleType = ImageView.ScaleType.CENTER_INSIDE
            cover.setColorFilter(Palette.colors.onSurfaceMuted)
            cover.setPadding(Views.dp(context, if (full) 34f else 10f))
            return
        }
        cover.clearColorFilter()
        cover.setPadding(0)
        cover.scaleType = ImageView.ScaleType.CENTER_CROP
        val radius = Views.dp(context, if (full) 16f else 12f).toFloat()
        cover.setImageBitmap(ImageCache.rounded(art, radius))
    }

    /** Volume + source are only correct when the card is visible. */
    override fun onPaletteChanged() {
        super.onPaletteChanged()
        playButton.background = Palette.fill(context, Palette.colors.accent, if (full) 31f else 18f)
        lastCoverKey = ""
        render(state)
    }

}

/**
 * Thin seek control: one filled track, a thumb, and a fat touch slop area (the row is 26 dp tall but
 * the draggable target is the full width) so it is usable at speed.
 */
class MediaSeekBar(context: Context, private val big: Boolean) : View(context) {

    var compact = false

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f
    private var shown = 0f
    private var enabled = false
    var scrubbing = false
        private set
    var onSeek: ((Float) -> Unit)? = null

    private val ticker = object : Runnable {
        override fun run() {
            val d = fraction - shown
            if (kotlin.math.abs(d) > 0.0005f && !scrubbing) {
                shown += d * 0.35f
                invalidate()
                postOnAnimation(this)
            } else {
                shown = fraction
                invalidate()
                postOnAnimationDelayed(this, 250L)
            }
        }
    }

    init {
        isClickable = true
        isFocusable = false
    }

    fun setProgress(f: Float, canSeek: Boolean) {
        fraction = f.coerceIn(0f, 1f)
        enabled = canSeek
        if (!enabled && scrubbing) scrubbing = false
        invalidate()
    }

    fun releaseScrub() {
        scrubbing = false
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val c = Palette.colors
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f) return
        val trackH = dp(if (compact) 3f else if (big) 5.5f else 4f)
        val cy = if (compact) h / 2f else h / 2f
        val pad = if (compact) 0f else dp(1.5f)
        val usable = w - pad * 2f

        rect.set(pad, cy - trackH / 2f, pad + usable, cy + trackH / 2f)
        track.color = Format.withAlpha(c.onSurface, if (c.night) 42 else 34)
        canvas.drawRoundRect(rect, trackH, trackH, track)

        val value = if (scrubbing) fraction else shown
        rect.set(pad, cy - trackH / 2f, pad + usable * value, cy + trackH / 2f)
        track.color = if (enabled || scrubbing) c.accent else Format.withAlpha(c.accent, 90)
        canvas.drawRoundRect(rect, trackH, trackH, track)

        if (!compact) {
            val r = dp(if (scrubbing) 8f else 6f)
            val x = pad + usable * value
            thumb.color = c.surfaceSolid
            canvas.drawCircle(x, cy, r + dp(1.6f), thumb)
            thumb.color = if (enabled || scrubbing) c.accent else Format.withAlpha(c.accent, 120)
            canvas.drawCircle(x, cy, r, thumb)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scrubbing = true
                parent?.requestDisallowInterceptTouchEvent(true)
                update(event.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                update(event.x)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                update(event.x)
                val f = fraction
                scrubbing = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) onSeek?.invoke(f)
            }
        }
        return true
    }

    private fun update(x: Float) {
        val pad = if (compact) 0f else dp(1.5f)
        val usable = width - pad * 2f
        if (usable <= 0f) return
        fraction = ((x - pad) / usable).coerceIn(0f, 1f)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
