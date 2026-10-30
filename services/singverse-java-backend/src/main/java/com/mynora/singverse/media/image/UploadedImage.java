package com.mynora.singverse.media.image;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Shared bounded JPEG/PNG decoder for administrator uploads only. */
public final class UploadedImage {
    private UploadedImage() {}

    public static byte[] normalize(MultipartFile file) {
        if (file == null
                || file.isEmpty()
                || file.getSize() > 2_000_000
                || !Set.of("image/jpeg", "image/png")
                        .contains(Objects.toString(file.getContentType(), "")))
            throw fail(400, "请选择2MB以内的JPEG或PNG图片");
        try (var stream = file.getInputStream()) {
            byte[] source = stream.readNBytes(2_000_001);
            if (source.length > 2_000_000) throw fail(400, "图片不能超过2MB");
            try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(source))) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw fail(400, "图片内容无法识别");
                var reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!Set.of("jpeg", "png").contains(format)
                            || !file.getContentType()
                                    .equals(format.equals("png") ? "image/png" : "image/jpeg"))
                        throw fail(400, "图片类型与实际内容不一致");
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 1 || h < 1 || w > 4096 || h > 4096) throw fail(400, "图片尺寸应在1至4096像素之间");
                    var decoded = reader.read(0);
                    double scale = Math.min(1.0, 1280.0 / Math.max(w, h));
                    var clean =
                            new BufferedImage(
                                    Math.max(1, (int) (w * scale)),
                                    Math.max(1, (int) (h * scale)),
                                    BufferedImage.TYPE_INT_RGB);
                    var graphics = clean.createGraphics();
                    try {
                        graphics.setColor(new Color(25, 27, 32));
                        graphics.fillRect(0, 0, clean.getWidth(), clean.getHeight());
                        graphics.setRenderingHint(
                                RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                        graphics.drawImage(
                                decoded, 0, 0, clean.getWidth(), clean.getHeight(), null);
                    } finally {
                        graphics.dispose();
                    }
                    var output = new ByteArrayOutputStream();
                    if (!ImageIO.write(clean, "jpeg", output)) throw fail(400, "图片无法保存");
                    return output.toByteArray();
                } finally {
                    reader.dispose();
                }
            }
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw fail(400, "图片无法读取，请重新选择JPEG或PNG");
        }
    }

    private static ResponseStatusException fail(int status, String reason) {
        return new ResponseStatusException(HttpStatus.valueOf(status), reason);
    }
}
