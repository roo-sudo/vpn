package com.dachui.vpn.controller;

import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 文件下载上传下载类
 * @author dachui
 */
@RestController
@RequestMapping("/file")
public class FileDownloadClass {

    private static final Logger log = LoggerFactory.getLogger(FileDownloadClass.class);

    @Value("${minio.endpoint}")
    private  String ENDPOINT;
    @Value("${minio.bucketName}")
    private  String BUCKETNAME;
    @Value("${minio.accessKey}")
    private  String ACCESSKEY;
    @Value("${minio.secretKey}")
    private  String SECRETKEY;

    //文件创建
    @PostMapping("/uploadFile")
    public String upload(MultipartFile file) {
        String s=null;
        try {
            MinioClient minioClient = new MinioClient(ENDPOINT, ACCESSKEY, SECRETKEY);
            //存入bucket不存在则创建，并设置为只读
            //低版本的minio依赖会在解析xml时报错
            if (!minioClient.bucketExists(BUCKETNAME)) {
                minioClient.makeBucket(BUCKETNAME);
                minioClient.setBucketPolicy(BUCKETNAME, "*.*");
            }
            String filename = file.getOriginalFilename();
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            // 文件存储的目录结构
            String objectName = sdf.format(new Date()) + "/" + filename;
            // 存储文件
            minioClient.putObject(
                    BUCKETNAME,
                    objectName,
                    file.getResource().getInputStream(),
                    file.getContentType());
            log.info("文件上传成功!");
            s=ENDPOINT + "/" + BUCKETNAME + "/" + objectName;
        } catch (Exception e) {
            log.info("上传发生错误: {}！", e.getMessage());
        }
        return s;
    }

    //文件删除
    @DeleteMapping
    public String delete(String name) {
        try {
            MinioClient minioClient = new MinioClient(ENDPOINT, ACCESSKEY, SECRETKEY);
            minioClient.removeObject(BUCKETNAME, name);
        } catch (Exception e) {
            return "删除失败"+e.getMessage();
        }
        return "删除成功";
    }


    @GetMapping("/downloadFiles")
    public void downloadFiles(@RequestParam("filename") String filename, HttpServletResponse httpResponse) {
        try {
            MinioClient minioClient = new MinioClient(ENDPOINT, ACCESSKEY, SECRETKEY);
            InputStream object = minioClient.getObject(BUCKETNAME, filename);
            byte buf[] = new byte[1024];
            int length = 0;
            httpResponse.reset();
            httpResponse.setHeader("Content-Disposition", "attachment;filename=" + URLEncoder.encode(filename, "UTF-8"));
            httpResponse.setContentType("application/octet-stream");
            httpResponse.setCharacterEncoding("utf-8");
            OutputStream outputStream = httpResponse.getOutputStream();
            while ((length = object.read(buf)) > 0) {
                outputStream.write(buf, 0, length);
            }
            outputStream.close();
        } catch (Exception ex) {
            log.info("文件下载失败：{}", ex.getMessage());
        }
    }
}
