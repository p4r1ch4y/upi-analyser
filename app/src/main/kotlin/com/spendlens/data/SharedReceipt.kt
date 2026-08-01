package com.spendlens.data

import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File

/**
 * Everything usable that arrived in a share intent.
 *
 * The share rail is the only one that catches a payment made on this phone, and
 * it is also the one that most often arrives as a screenshot rather than as text.
 * So this pulls apart the intent rather than reading one extra: text hides in the
 * clip data as often as it does in EXTRA_TEXT, the subject line sometimes carries
 * the amount, and the screenshot's own timestamp is a better guess at when the
 * payment happened than "now" is.
 */
data class SharedReceipt(
    /** Every distinct piece of text the intent carried, longest first. */
    val texts: List<String>,
    /** A copy of the shared image inside this app's cache, if one came. */
    val imageFile: File?,
    /** When the image was created, which for a receipt screenshot is payment time. */
    val imageTakenAt: Long?,
    /**
     * The name the sending app gave the file.
     *
     * Worth keeping because Google Pay names a shared receipt after the payment
     * it shows - `1738737495 - 165.00 To Krishnendu Diyan on Google Pay.png` - so
     * for that app the whole transaction is readable without touching a pixel.
     * See [com.spendlens.core.parser.ReceiptFileName].
     */
    val imageName: String?,
    /** True when the share carried an image this app could not open. */
    val imageUnreadable: Boolean,
    /** The package that shared it, when the sender chose to say. */
    val sourcePackage: String?
) {
    val hasImage: Boolean get() = imageFile != null
    val hasText: Boolean get() = texts.isNotEmpty()
    val isEmpty: Boolean get() = !hasImage && !hasText

    /**
     * The texts joined, for a parser that reads a whole message.
     *
     * Offered alongside the individual pieces because a UPI app that shares
     * "Paid to Anita" as the subject and "₹450 · 12:04 PM" as the body has said
     * the whole thing between them and neither half parses alone.
     */
    val combined: String get() = texts.joinToString("\n")
}

/**
 * Reads a share intent into something the app can act on.
 *
 * The image is copied into private cache rather than kept as a URI. A URI grant
 * made to a `noHistory` activity that finishes immediately does not reliably
 * outlive it, and the receipt has to survive long enough for the user to read it
 * while typing - across a rotation, and across the process being killed behind
 * the keyboard.
 */
object SharedReceiptReader {

    /** Where staged receipts live. Private to the app, and swept on every share. */
    private const val CACHE_DIR = "shared_receipts"

    /** A receipt nobody finished typing up is rubbish within the hour. */
    private const val STALE_MILLIS = 60L * 60 * 1000

    /** Beyond this a "screenshot" is something else, and is not copied. */
    private const val MAX_BYTES = 12L * 1024 * 1024

    fun read(context: Context, intent: Intent?): SharedReceipt {
        if (intent == null) return EMPTY

        sweep(context)

        val texts = textsIn(intent)
        val imageUri = imagesIn(intent).firstOrNull()
        val staged = imageUri?.let { stage(context, it) }

        return SharedReceipt(
            texts = texts,
            imageFile = staged,
            imageTakenAt = imageUri?.let { takenAt(context, it) },
            imageName = imageUri?.let { displayName(context, it) },
            // An image arrived and could not be opened. Distinct from no image at
            // all, because "nothing was shared" is the wrong thing to tell someone
            // who just shared something.
            imageUnreadable = imageUri != null && staged == null,
            sourcePackage = intent.getStringExtra(EXTRA_SOURCE_PACKAGE)
        )
    }

    private val EMPTY = SharedReceipt(
        texts = emptyList(),
        imageFile = null,
        imageTakenAt = null,
        imageName = null,
        imageUnreadable = false,
        sourcePackage = null
    )

    /**
     * The name the sending app gave the file.
     *
     * Falls back to the URI's last path segment, because a `file://` share has no
     * content provider to ask and the segment is the name.
     */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@runCatching uri.lastPathSegment
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Every text the intent carries, de-duplicated, longest first.
     *
     * Longest first because when an app sends both a subject and a body the body
     * is the one with the payment in it, and the parser should be offered that
     * before "Payment receipt".
     */
    private fun textsIn(intent: Intent): List<String> {
        val found = mutableListOf<String?>()
        found += intent.getStringExtra(Intent.EXTRA_TEXT)
        found += intent.getStringExtra(Intent.EXTRA_SUBJECT)
        found += intent.getStringExtra(Intent.EXTRA_TITLE)

        // Text alongside an image lives here rather than in EXTRA_TEXT for a
        // good number of apps, and reading only the extra is why sharing from
        // them landed on an empty form.
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                found += clip.getItemAt(index).text?.toString()
            }
        }

        intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.forEach { found += it.toString() }

        return found
            .mapNotNull { it?.trim()?.takeIf { text -> text.isNotEmpty() } }
            .distinct()
            .sortedByDescending { it.length }
    }

    /** Image URIs from a single or multiple share, and from the clip data. */
    private fun imagesIn(intent: Intent): List<Uri> {
        val found = mutableListOf<Uri?>()
        found += intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            found += intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
        intent.clipData?.let { clip: ClipData ->
            for (index in 0 until clip.itemCount) found += clip.getItemAt(index).uri
        }
        return found.filterNotNull().distinct()
    }

    /**
     * Copies the shared image into private cache and returns the file.
     *
     * Null on anything that cannot be read or is implausibly large, rather than
     * an exception: a share that fails here still has a form to fall back to.
     */
    private fun stage(context: Context, uri: Uri): File? = runCatching {
        val directory = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val target = File(directory, "receipt-${System.currentTimeMillis()}.img")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                var copied = 0L
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    copied += read
                    if (copied > MAX_BYTES) {
                        target.delete()
                        return@runCatching null
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: return@runCatching null

        // Verify it decodes as an image before promising the UI one. `justDecode`
        // reads only the header, so this costs nothing.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            target.delete()
            return@runCatching null
        }
        target
    }.getOrNull()

    /**
     * When the shared image was made.
     *
     * A receipt screenshot is taken seconds after the payment, so this is a far
     * better default for the entry's date and time than the moment the user got
     * round to sharing it - which may be the following evening.
     */
    private fun takenAt(context: Context, uri: Uri): Long? = runCatching {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@runCatching null
        context.contentResolver.query(
            uri,
            arrayOf(DATE_TAKEN, DATE_MODIFIED, DATE_ADDED),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // DATE_TAKEN is already in millis; the other two are unix seconds.
            cursor.longOrNull(DATE_TAKEN)
                ?: cursor.longOrNull(DATE_MODIFIED)?.times(1000)
                ?: cursor.longOrNull(DATE_ADDED)?.times(1000)
        }
    }.getOrNull()?.takeIf { it > 0L }

    private fun android.database.Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return getLong(index).takeIf { it > 0L }
    }

    /**
     * Deletes staged receipts older than an hour, and the named one outright.
     *
     * The app holds a copy of a payment screenshot for exactly as long as the
     * form in front of the user needs it. Leaving them to accumulate would build
     * a folder of the user's receipts that nothing ever reads.
     */
    fun discard(context: Context, file: File?) {
        runCatching { file?.delete() }
        sweep(context)
    }

    private fun sweep(context: Context) = runCatching {
        val directory = File(context.cacheDir, CACHE_DIR)
        val cutoff = System.currentTimeMillis() - STALE_MILLIS
        directory.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.delete()
        }
    }

    /**
     * Decodes a staged receipt, downsampled to roughly [maxDimension].
     *
     * A modern phone screenshot is 1440x3200 and would be ~18 MB as a bitmap;
     * the preview is a few hundred pixels tall and does not need any of that.
     */
    fun decode(file: File, maxDimension: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2

        BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }.getOrNull()

    const val EXTRA_SOURCE_PACKAGE = "source_package"

    // Read as literals rather than through MediaStore.* so this compiles the same
    // whether the URI came from the media store or from a file provider.
    private const val DATE_TAKEN = "datetaken"
    private const val DATE_MODIFIED = "date_modified"
    private const val DATE_ADDED = "date_added"

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, T::class.java)
        } else {
            getParcelableExtra(name) as? T
        }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(
        name: String
    ): ArrayList<T>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(name, T::class.java)
        } else {
            getParcelableArrayListExtra(name)
        }
}
