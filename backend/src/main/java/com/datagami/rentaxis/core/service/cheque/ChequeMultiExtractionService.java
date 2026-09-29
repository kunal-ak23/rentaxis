package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.dto.ChequeBoundingBoxDTO;
import com.datagami.rentaxis.api.dto.ChequeExtractionPageDTO;
import com.datagami.rentaxis.api.dto.ChequeImageMetaDTO;
import com.datagami.rentaxis.api.dto.ChequeMultiExtractionResponseDTO;
import com.datagami.rentaxis.api.dto.DetectedChequeItemDTO;
import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.BoundingBox;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.DetectedCheque;
import com.datagami.rentaxis.core.service.cheque.ChequeExtractor.MultiExtractionResult;
import com.datagami.rentaxis.domain.entity.ChequeImageUpload;
import com.datagami.rentaxis.domain.repository.ChequeImageUploadRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code POST /api/v1/cheques/extract-many}: one photo or PDF in, one issued image
 * per cheque out.
 *
 * <p><b>Order.</b> Everything that can refuse the upload (type, size, page count,
 * cheque count) runs before anything is stored, so a refusal leaves no blob and no
 * issued row behind. Then the original is stored, then one image per cheque.</p>
 *
 * <p><b>What each cheque's image is.</b></p>
 * <ul>
 *   <li>A photo with one cheque (or none found): the original upload itself,
 *       attachable — exactly what {@code /extract} issues.</li>
 *   <li>A photo with several: one crop per cheque, each issued as its own image
 *       linked to the original; the original is then <i>not</i> attachable,
 *       because attaching it puts every cheque on one instalment.</li>
 *   <li>A PDF: each page is rendered, then treated like a photo — except that a
 *       page with one cheque issues the rendered page (a PDF is never attachable).</li>
 *   <li>A cheque the cropper will not cut cleanly: the whole page, issued as its
 *       own image, flagged {@code crop_unreliable}. Never a guessed crop.</li>
 * </ul>
 *
 * <p><b>Tenancy.</b> Every blob goes to the caller's tenant container and every
 * issued row carries the caller's tenant id; bulk-attach accepts only rows of the
 * caller's tenant (audit C-F2). This service reads nothing from the database.</p>
 *
 * <p>Not {@code @Transactional}: the model call and the blob uploads are slow
 * network work that must not hold a connection.</p>
 */
@Service
@Slf4j
public class ChequeMultiExtractionService {

    static final String CROP_UNRELIABLE_WARNING = "Could not separate this cheque cleanly — check the crop";
    private static final String PDF = "application/pdf";

    private final BlobStorageService blobStorage;
    private final ChequeExtractor extractor;
    private final ChequeImageUploadRepository uploads;

    /**
     * PDF input is off until the renderer is hardened against PDF bombs (inline
     * images, soft masks, pattern/annotation resources, huge content streams —
     * see PR #388). Off, a PDF is refused with {@code cheque_upload_pdf_not_supported}.
     */
    @Value("${rentaxis.cheques.pdf-upload.enabled:false}")
    boolean pdfUploadEnabled = false;

    @Value("${cheque-extraction.max-pdf-pages:6}")
    int maxPdfPages = 6;

    /**
     * Cheques one page may hold. The model's output budget is sized for this many
     * ({@link AzureOpenAIChequeExtractor#MAX_CHEQUES_PER_PAGE}); more would be cut off.
     */
    int maxChequesPerPage = AzureOpenAIChequeExtractor.MAX_CHEQUES_PER_PAGE;

    /** Pages read at once: gentle on the Azure rate limit (the bulk flow already runs 2 files at once). */
    @Value("${cheque-extraction.page-parallelism:3}")
    int pageParallelism = 3;

    /**
     * Wall-clock budget for reading every page. Well inside the web proxy's 300 s:
     * a page still unread when it runs out yields one blank row to type in, and the
     * request still answers — no half-stored upload behind a timed-out request.
     */
    @Value("${cheque-extraction.read-budget-seconds:150}")
    long readBudgetSeconds = 150;

    @Value("${cheque-extraction.max-cheques-per-upload:24}")
    int maxChequesPerUpload = 24;

    public ChequeMultiExtractionService(BlobStorageService blobStorage, ChequeExtractor extractor,
                                        ChequeImageUploadRepository uploads) {
        this.blobStorage = blobStorage;
        this.extractor = extractor;
        this.uploads = uploads;
    }

    /**
     * Decoding and cropping hold whole images on the heap (up to the cropper's
     * ~48 MB decode budget, plus copies). At most this many uploads do it at once,
     * so concurrent uploads cannot stack their images; others wait their turn.
     */
    static final int IMAGE_WORK_PERMITS = 2;
    private final java.util.concurrent.Semaphore imageWork = new java.util.concurrent.Semaphore(IMAGE_WORK_PERMITS, true);

    private <T> T withImageWork(java.util.function.Supplier<T> work) {
        boolean acquired;
        try {
            acquired = imageWork.tryAcquire(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.BUSY,
                    "Too many cheque uploads are being processed; try again shortly");
        }
        try {
            return work.get();
        } finally {
            imageWork.release();
        }
    }

    /** One page to read: the bytes the model is shown, and their type. */
    private record Page(int number, byte[] bytes, String contentType) {}

    public ChequeMultiExtractionResponseDTO extractAndStore(UUID tenantId, MultipartFile file) {
        String contentType = validate(file);
        boolean pdf = PDF.equals(contentType);
        byte[] original;
        try {
            original = file.getBytes();
        } catch (IOException e) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.FILE_REQUIRED, "The file could not be read");
        }

        // --- Pages, and what the model is shown for each -----------------------
        List<Page> pages = new ArrayList<>();
        if (pdf) {
            List<byte[]> rendered;
            try {
                rendered = ChequePdfRenderer.renderToJpeg(original, maxPdfPages);
            } catch (ChequePdfRenderer.PdfRefusedException e) {
                throw new ChequeUploadRefusedException(e.code(), e.getMessage());
            }
            for (int i = 0; i < rendered.size(); i++) {
                pages.add(new Page(i + 1, rendered.get(i), "image/jpeg"));
            }
        } else {
            // Decoded here only to refuse a bomb and to turn an EXIF-rotated photo
            // upright for the model; the pixels are dropped before the (slow) model
            // call and decoded again for cropping, so no decoded image is held while
            // waiting on the network.
            byte[] upright = withImageWork(() -> {
                BufferedImage photo = ChequeImageCropper.decode(original);
                boolean turned = photo != null && ChequeImageCropper.exifOrientation(original) > 1;
                return turned ? ChequeImageCropper.toJpeg(photo, 0.92f) : null;
            });
            // The model must see the pixels the boxes are applied to: an EXIF-turned
            // photo is shown upright. Any other photo is sent exactly as uploaded.
            pages.add(upright != null
                    ? new Page(1, upright, "image/jpeg")
                    : new Page(1, original, contentType));
        }

        // --- Read every page before storing anything ---------------------------
        List<MultiExtractionResult> results = readAll(pages);
        int total = 0;
        for (MultiExtractionResult r : results) {
            if (r.cheques().size() > maxChequesPerPage) {
                throw new ChequeUploadRefusedException(ChequeUploadRefusedException.TOO_MANY_CHEQUES,
                        r.cheques().size() + " cheques on one page; at most " + maxChequesPerPage);
            }
            total += Math.max(1, r.cheques().size());
        }
        if (total > maxChequesPerUpload) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.TOO_MANY_CHEQUES,
                    total + " cheques found; at most " + maxChequesPerUpload + " per upload");
        }

        // --- Store: all of it, or none of it ----------------------------------
        List<ChequeImageUpload> stored = new ArrayList<>();
        try {
            return withImageWork(() ->
                    store(tenantId, file, contentType, pdf, original, pages, results, stored));
        } catch (RuntimeException | Error e) {
            // Nothing half-issued survives a failed store: the rows go (crops
            // first, they point at the original) and so do their blobs.
            for (int i = stored.size() - 1; i >= 0; i--) {
                ChequeImageUpload u = stored.get(i);
                try {
                    uploads.delete(u);
                } catch (RuntimeException ignored) {
                    // best effort; the blob delete below still runs
                }
                try {
                    blobStorage.delete(tenantId, u.getBlobPath());
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
            throw e;
        }
    }

    private ChequeMultiExtractionResponseDTO store(UUID tenantId, MultipartFile file, String contentType,
                                                   boolean pdf, byte[] original,
                                                   List<Page> pages, List<MultiExtractionResult> results,
                                                   List<ChequeImageUpload> stored) {
        // --- Store the original ------------------------------------------------
        boolean originalIsTheScan = !pdf && results.getFirst().cheques().size() <= 1;
        var up = blobStorage.uploadCheque(tenantId, file);
        OffsetDateTime uploadedAt = OffsetDateTime.now();
        ChequeImageUpload originalRow = new ChequeImageUpload();
        originalRow.setTenantId(tenantId);
        originalRow.setBlobPath(up.blobPath());
        originalRow.setImageUrl(up.url());
        originalRow.setUploadedBy(callerIdOrNull());
        originalRow.setAttachable(originalIsTheScan);
        stored.add(originalRow); // before save: a failed save still has its blob removed
        originalRow = uploads.save(originalRow);
        ChequeImageMetaDTO originalMeta = new ChequeImageMetaDTO(up.url(), up.blobPath(), uploadedAt);

        // --- One issued image per cheque ---------------------------------------
        List<ChequeExtractionPageDTO> pageDtos = new ArrayList<>();
        List<DetectedChequeItemDTO> items = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (int pi = 0; pi < pages.size(); pi++) {
            Page page = pages.get(pi);
            MultiExtractionResult r = results.get(pi);
            BufferedImage img = ChequeImageCropper.decode(pdf ? page.bytes() : original);
            pageDtos.add(new ChequeExtractionPageDTO(page.number(),
                    img == null ? null : ChequeImageCropper.dataUrl(img, ChequeImageCropper.PREVIEW_MAX)));
            for (String w : r.warnings()) {
                warnings.add(pdf ? "Page " + page.number() + ": " + w : w);
            }
            List<DetectedCheque> found = r.cheques();

            if (found.size() <= 1) {
                DetectedCheque one = found.isEmpty() ? null : found.getFirst();
                ChequeImageMetaDTO meta;
                UUID imageId;
                if (originalIsTheScan) {
                    meta = originalMeta;
                    imageId = originalRow.getId();
                } else {
                    ChequeImageUpload row = storeDerived(tenantId, originalRow, page.bytes(), ".jpg",
                            pdf ? page.number() : null, stored);
                    meta = new ChequeImageMetaDTO(row.getImageUrl(), row.getBlobPath(), uploadedAt);
                    imageId = row.getId();
                }
                List<String> itemWarnings = one == null ? r.warnings() : safeList(one.warnings());
                items.add(new DetectedChequeItemDTO(imageId, meta, page.number(),
                        one == null ? null : toDto(one.box()),
                        img == null ? null : ChequeImageCropper.dataUrl(img, ChequeImageCropper.THUMB_MAX),
                        one == null ? null : one.extracted(),
                        List.copyOf(itemWarnings), List.of()));
                continue;
            }

            List<ChequeImageCropper.Crop> plan = img == null ? null
                    : ChequeImageCropper.plan(found.stream().map(DetectedCheque::box).toList(),
                            img.getWidth(), img.getHeight());
            for (int ci = 0; ci < found.size(); ci++) {
                DetectedCheque det = found.get(ci);
                ChequeImageCropper.Crop crop = plan == null ? null : plan.get(ci);
                boolean clean = crop != null && crop.ok();
                byte[] bytes;
                String ext;
                BufferedImage shown;
                if (clean) {
                    shown = ChequeImageCropper.cut(img, crop);
                    bytes = ChequeImageCropper.toJpeg(shown, 0.9f);
                    ext = ".jpg";
                } else if (img != null) {
                    shown = img;
                    bytes = pdf ? page.bytes() : ChequeImageCropper.toJpeg(img, 0.9f);
                    ext = ".jpg";
                } else {
                    // Undecodable (HEIC): a copy of the original, so each cheque
                    // still has an image of its own to attach.
                    shown = null;
                    bytes = original;
                    ext = extensionFor(contentType);
                }
                ChequeImageUpload row = storeDerived(tenantId, originalRow, bytes, ext, pdf ? page.number() : null,
                        stored);
                List<String> itemWarnings = new ArrayList<>(safeList(det.warnings()));
                if (!clean) {
                    itemWarnings.add(CROP_UNRELIABLE_WARNING);
                }
                items.add(new DetectedChequeItemDTO(row.getId(),
                        new ChequeImageMetaDTO(row.getImageUrl(), row.getBlobPath(), uploadedAt),
                        page.number(), toDto(det.box()),
                        shown == null ? null : ChequeImageCropper.dataUrl(shown, ChequeImageCropper.THUMB_MAX),
                        det.extracted(), List.copyOf(itemWarnings),
                        clean ? List.of() : List.of(DetectedChequeItemDTO.FLAG_CROP_UNRELIABLE)));
            }
        }
        return new ChequeMultiExtractionResponseDTO(originalMeta, pageDtos, items, warnings);
    }

    /**
     * Every page, read {@link #pageParallelism} at a time within
     * {@link #readBudgetSeconds}. A page not read in time is cancelled and answers
     * no cheques with a warning, so the operator gets a blank row for it.
     */
    private List<MultiExtractionResult> readAll(List<Page> pages) {
        if (pages.size() == 1) {
            return List.of(safeExtractAll(pages.getFirst()));
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(pageParallelism, pages.size())));
        try {
            List<Future<MultiExtractionResult>> futures = new ArrayList<>();
            for (Page p : pages) {
                futures.add(pool.submit(() -> safeExtractAll(p)));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(readBudgetSeconds);
            List<MultiExtractionResult> out = new ArrayList<>();
            for (Future<MultiExtractionResult> f : futures) {
                long left = Math.max(0, deadline - System.nanoTime());
                try {
                    out.add(f.get(left, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    f.cancel(true);
                    out.add(new MultiExtractionResult(List.of(), List.of(READ_TIMED_OUT_WARNING)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    out.add(new MultiExtractionResult(List.of(), List.of(READ_TIMED_OUT_WARNING)));
                } catch (ExecutionException e) {
                    out.add(new MultiExtractionResult(List.of(), List.of("Extraction failed unexpectedly")));
                }
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    static final String READ_TIMED_OUT_WARNING = "This page took too long to read — fill it in by hand";

    private MultiExtractionResult safeExtractAll(Page page) {
        try {
            MultiExtractionResult r = extractor.extractAll(page.bytes(), page.contentType());
            if (r == null) {
                return new MultiExtractionResult(List.of(), List.of("Extraction failed unexpectedly"));
            }
            return new MultiExtractionResult(safeList(r.cheques()), safeList(r.warnings()));
        } catch (Exception e) {
            log.warn("Cheque extractor threw unexpectedly", e);
            return new MultiExtractionResult(List.of(), List.of("Extraction failed unexpectedly"));
        }
    }

    private ChequeImageUpload storeDerived(UUID tenantId, ChequeImageUpload source, byte[] bytes, String ext,
                                           Integer pageNumber, List<ChequeImageUpload> stored) {
        var up = blobStorage.uploadChequeBytes(tenantId, bytes, ext);
        ChequeImageUpload row = new ChequeImageUpload();
        row.setTenantId(tenantId);
        row.setBlobPath(up.blobPath());
        row.setImageUrl(up.url());
        row.setUploadedBy(source.getUploadedBy());
        row.setSourceUploadId(source.getId());
        row.setPageNumber(pageNumber);
        row.setAttachable(true);
        stored.add(row);
        return uploads.save(row);
    }

    private String validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.FILE_REQUIRED,
                    "file is required and must not be empty");
        }
        if (file.getSize() > ChequeExtractionService.MAX_BYTES) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.FILE_TOO_LARGE, "file too large; max 10MB");
        }
        String contentType = file.getContentType() == null ? null : file.getContentType().toLowerCase(Locale.ROOT);
        if (contentType == null
                || !(PDF.equals(contentType) || ChequeExtractionService.ALLOWED_TYPES.contains(contentType))) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.UNSUPPORTED_TYPE,
                    "Unsupported file type: " + file.getContentType());
        }
        if (PDF.equals(contentType) && !pdfUploadEnabled) {
            throw new ChequeUploadRefusedException(ChequeUploadRefusedException.PDF_NOT_SUPPORTED,
                    "PDF uploads are not enabled; upload photos of the cheques instead");
        }
        return contentType;
    }

    private static ChequeBoundingBoxDTO toDto(BoundingBox b) {
        return b == null ? null : new ChequeBoundingBoxDTO(b.x(), b.y(), b.width(), b.height());
    }

    private static <T> List<T> safeList(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** The stored extension, from the validated content type — never the client's file name. */
    static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/heic" -> ".heic";
            case "image/heif" -> ".heif";
            default -> ".jpg";
        };
    }

    private static UUID callerIdOrNull() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        try {
            return auth == null ? null : UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
