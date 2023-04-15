package com.jmal.clouddisk.oss.web;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.io.file.PathUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.URLUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jmal.clouddisk.exception.CommonException;
import com.jmal.clouddisk.exception.ExceptionType;
import com.jmal.clouddisk.model.*;
import com.jmal.clouddisk.oss.*;
import com.jmal.clouddisk.util.CaffeineUtil;
import com.jmal.clouddisk.util.FileContentTypeUtils;
import com.jmal.clouddisk.util.ResponseResult;
import com.jmal.clouddisk.util.ResultUtil;
import com.jmal.clouddisk.webdav.MyWebdavServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriUtils;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
@Slf4j
public class WebOssService extends WebOssCommonService {

    /***
     * 断点恢复上传缓存(已上传的分片缓存)
     * key: uploadId
     * value: 已上传的分片号列表
     */
    private static final Cache<String, CopyOnWriteArrayList<Integer>> LIST_PARTS_CACHE = Caffeine.newBuilder().build();

    public List<Map<String, String>> getPlatformList() {
        List<Map<String, String>> maps = new ArrayList<>(PlatformOSS.values().length);
        for (PlatformOSS platformOSS : PlatformOSS.values()) {
            Map<String, String> map = new HashMap<>(2);
            map.put("value", platformOSS.getKey());
            map.put("label", platformOSS.getValue());
            maps.add(map);
        }
        return maps;
    }


    public static String getObjectName(Path prePath, String ossPath, boolean isFolder) {
        String name = "";
        int ossPathCount = Paths.get(ossPath).getNameCount();
        if (prePath.getNameCount() > ossPathCount) {
            name = prePath.subpath(ossPathCount, prePath.getNameCount()).toString();
            if (!name.endsWith("/") && isFolder) {
                name = name + "/";
            }
        }
        return URLUtil.decode(name);
    }

    public ResponseResult<Object> searchFileAndOpenOssFolder(Path prePth, UploadApiParamDTO upload) {
        List<FileIntroVO> fileIntroVOList = getOssFileList(prePth, upload);
        ResponseResult<Object> result = ResultUtil.genResult();
        result.setCount(fileIntroVOList.size());
        result.setData(fileIntroVOList);
        return result;
    }

    public List<FileIntroVO> getOssFileList(Path prePth, UploadApiParamDTO upload) {
        List<FileIntroVO> fileIntroVOList = new ArrayList<>();
        String ossPath = CaffeineUtil.getOssPath(prePth);
        if (ossPath == null) {
            return fileIntroVOList;
        }
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, true);
        List<FileInfo> list = ossService.getFileInfoListCache(objectName);
        if (!list.isEmpty()) {
            String userId = null;
            if (upload != null) {
                userId = upload.getUserId();
            }
            if (CharSequenceUtil.isNotBlank(userId)) {
                userId = userService.getUserIdByUserName(getUsernameByOssPath(ossPath));
            }
            String finalUserId = userId;
            fileIntroVOList = list.stream().map(fileInfo -> fileInfo.toFileIntroVO(ossPath, finalUserId)).toList();
            // 排序
            fileIntroVOList = getSortFileList(upload, fileIntroVOList);
            // 分页
            if (upload != null && upload.getPageIndex() != null && upload.getPageSize() != null) {
                fileIntroVOList = getPageFileList(fileIntroVOList, upload.getPageSize(), upload.getPageIndex());
            }
            // 其他过滤条件
            fileIntroVOList = filterOther(upload, fileIntroVOList);
        }
        return fileIntroVOList;
    }

    private static List<FileIntroVO> filterOther(UploadApiParamDTO upload, List<FileIntroVO> fileIntroVOList) {
        if (upload != null) {
            if (BooleanUtil.isTrue(upload.getJustShowFolder())) {
                // 只显示文件夹
                fileIntroVOList = fileIntroVOList.stream().filter(FileIntroVO::getIsFolder).toList();
            }
            if (BooleanUtil.isTrue(upload.getPathAttachFileName())) {
                // path附加文件名
                List<FileIntroVO> list = new ArrayList<>();
                for (FileIntroVO fileIntroVO : fileIntroVOList) {
                    fileIntroVO.setPath(fileIntroVO.getPath() + fileIntroVO.getName());
                    list.add(fileIntroVO);
                }
                fileIntroVOList = list;
            }
        }
        return fileIntroVOList;
    }

    private List<FileIntroVO> getSortFileList(UploadApiParamDTO upload, List<FileIntroVO> fileIntroVOList) {
        if (upload == null) {
            return fileIntroVOList;
        }
        String order = upload.getOrder();
        if (!CharSequenceUtil.isBlank(order)) {
            String sortableProp = upload.getSortableProp();
            // 按文件大小排序
            if ("size".equals(sortableProp)) {
                if ("descending".equals(order)) {
                    // 倒序
                    fileIntroVOList = fileIntroVOList.stream().sorted(commonFileService::compareBySizeDesc).toList();
                } else {
                    // 正序
                    fileIntroVOList = fileIntroVOList.stream().sorted(commonFileService::compareBySize).toList();
                }
            }
            // 按文件最近修改时间排序
            if ("updateDate".equals(sortableProp)) {
                if ("descending".equals(order)) {
                    // 倒序
                    fileIntroVOList = fileIntroVOList.stream().sorted(commonFileService::compareByUpdateDateDesc).toList();
                } else {
                    // 正序
                    fileIntroVOList = fileIntroVOList.stream().sorted(commonFileService::compareByUpdateDate).toList();
                }
            }
        }
        // 默认按文件排序
        fileIntroVOList = commonFileService.sortByFileName(upload, fileIntroVOList, order);
        return fileIntroVOList;
    }

    public List<FileIntroVO> getPageFileList(List<FileIntroVO> fileIntroVOList, int pageSize, int pageIndex) {
        List<FileIntroVO> pageList = new ArrayList<>();
        int startIndex = (pageIndex - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, fileIntroVOList.size());
        for (int i = startIndex; i < endIndex; i++) {
            pageList.add(fileIntroVOList.get(i));
        }
        return pageList;
    }

    public Optional<FileIntroVO> readToText(String ossPath, Path prePth) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);
        try (AbstractOssObject abstractOssObject = ossService.getAbstractOssObject(objectName);
             InputStream inputStream = abstractOssObject.getInputStream()) {
            FileIntroVO fileIntroVO = new FileIntroVO();
            FileInfo fileInfo = abstractOssObject.getFileInfo();
            String context;
            if (fileInfo != null && inputStream != null) {
                String userId = userService.getUserIdByUserName(getUsernameByOssPath(ossPath));
                fileIntroVO = fileInfo.toFileIntroVO(ossPath, userId);
                context = IoUtil.read(inputStream, StandardCharsets.UTF_8);
                fileIntroVO.setContentText(context);
            }
            return Optional.of(fileIntroVO);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return Optional.empty();
    }

    public UploadResponse checkChunk(String ossPath, Path prePth, UploadApiParamDTO upload) {
        UploadResponse uploadResponse = new UploadResponse();
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);
        if (ossService.doesObjectExist(objectName)) {
            // 对象已存在
            uploadResponse.setPass(true);
        } else {
            String uploadId = ossService.getUploadId(objectName);
            // 已上传的分片号
            List<Integer> chunks = LIST_PARTS_CACHE.get(uploadId, key -> ossService.getListParts(objectName, uploadId));
            // 返回已存在的分片
            uploadResponse.setResume(chunks);
            assert chunks != null;
            if (upload.getTotalChunks() == chunks.size()) {
                // 文件不存在,并且已经上传了所有的分片,则合并保存文件
                ossService.completeMultipartUpload(objectName, uploadId, upload.getTotalSize());
                notifyCreateFile(upload.getUsername(), objectName, getOssRootFolderName(ossPath));
            }
        }
        uploadResponse.setUpload(true);
        return uploadResponse;
    }

    public UploadResponse mergeFile(String ossPath, Path prePth, UploadApiParamDTO upload) {
        UploadResponse uploadResponse = new UploadResponse();

        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);
        ossService.completeMultipartUpload(objectName, ossService.getUploadId(objectName), upload.getTotalSize());
        notifyCreateFile(upload.getUsername(), objectName, getOssRootFolderName(ossPath));
        uploadResponse.setUpload(true);
        return uploadResponse;
    }

    public UploadResponse upload(String ossPath, Path prePth, UploadApiParamDTO upload) throws IOException {
        UploadResponse uploadResponse = new UploadResponse();

        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);

        int currentChunkSize = upload.getCurrentChunkSize();
        long totalSize = upload.getTotalSize();
        MultipartFile file = upload.getFile();
        if (currentChunkSize == totalSize) {
            // 没有分片,直接存
            ossService.uploadFile(file.getInputStream(), objectName, currentChunkSize);
            notifyCreateFile(upload.getUsername(), objectName, getOssRootFolderName(ossPath));
        } else {
            // 上传分片
            String uploadId = ossService.getUploadId(objectName);

            // 已上传的分片号列表
            CopyOnWriteArrayList<Integer> chunks = LIST_PARTS_CACHE.get(uploadId, key -> ossService.getListParts(objectName, uploadId));

            // 上传本次的分片
            boolean success = ossService.uploadPart(file.getInputStream(), objectName, currentChunkSize, upload.getChunkNumber(), uploadId);
            if (!success) {
                // 上传分片失败
                uploadResponse.setUpload(false);
                return uploadResponse;
            }

            // 加入缓存
            if (chunks != null) {
                chunks.add(upload.getChunkNumber());
                LIST_PARTS_CACHE.put(uploadId, chunks);
                // 检测是否已经上传完了所有分片,上传完了则需要合并
                if (chunks.size() == upload.getTotalChunks()) {
                    uploadResponse.setMerge(true);
                }
            }
        }
        uploadResponse.setUpload(true);
        return uploadResponse;
    }

    public String mkdir(String ossPath, Path prePth) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, true);
        ossService.mkdir(objectName);
        String username = getUsernameByOssPath(ossPath);
        notifyCreateFile(username, objectName, getOssRootFolderName(ossPath));
        return ossPath.substring(1) + MyWebdavServlet.PATH_DELIMITER + objectName;
    }

    public void rename(String ossPath, String pathName, String newFileName) {
        boolean isFolder = pathName.endsWith("/");
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = pathName.substring(ossPath.length());
        Path newFilePath = Paths.get(Paths.get(pathName).getParent().toString(), newFileName);
        String destinationObjectName;
        if (!isFolder) {
            destinationObjectName = getObjectName(newFilePath, ossPath, false);
        } else {
            destinationObjectName = getObjectName(newFilePath, ossPath, true);
        }
        // 复制
        if (ossService.copyObject(objectName, destinationObjectName)) {
            // 删除
            ossService.delete(objectName);
        }
        notifyCreateFile(getUsernameByOssPath(ossPath), objectName, getOssRootFolderName(ossPath));
        String rootFolderName = getOssRootFolderName(ossPath);
        Path fromPath = Paths.get(rootFolderName, objectName);
        Path toPath = Paths.get(rootFolderName, newFileName);
        commonFileService.pushMessageOperationFileSuccess(fromPath.toString(), toPath.toString(), getUsernameByOssPath(ossPath), "重命名");
        renameAfter(ossPath, pathName, isFolder, objectName, newFilePath);
    }

    private void renameAfter(String ossPath, String pathName, boolean isFolder, String objectName, Path newFilePath) {
        // 删除临时文件，如果有的话
        deleteTemp(ossPath, objectName);
        // 检查该目录是否有其他依赖的缓存等等。。
        Query query = new Query();
        query.addCriteria(Criteria.where("_id").regex("^" + pathName));
        List<FileDocument> fileDocumentList = mongoTemplate.findAllAndRemove(query, FileDocument.class);
        String newPathName = newFilePath.toString();
        if (isFolder) {
            newPathName += MyWebdavServlet.PATH_DELIMITER;
        }
        // 修改关联的分享文件
        String finalNewPathName = newPathName;
        String username = getUsernameByOssPath(ossPath);
        String oldPath = pathName.substring(username.length());
        List<FileDocument> newList = new ArrayList<>();
        for (FileDocument fileDocument : fileDocumentList) {
            String oldId = fileDocument.getId();
            String newId = CharSequenceUtil.replace(oldId, pathName, finalNewPathName);
            String newPath = CharSequenceUtil.replace(fileDocument.getPath(), oldPath, finalNewPathName);
            fileDocument.setId(newId);
            fileDocument.setPath(newPath);
            if (pathName.endsWith(oldId)) {
                fileDocument.setName(newFilePath.getFileName().toString());
            }
            newList.add(fileDocument);
        }
        if (!newList.isEmpty()) {
            mongoTemplate.insertAll(newList);
        }
        // 修改关联的分享配置
        Query shareQuery = new Query();
        shareQuery.addCriteria(Criteria.where("fileId").regex("^" + pathName));
        List<ShareDO> shareDOList = mongoTemplate.findAllAndRemove(shareQuery, ShareDO.class);
        List<ShareDO> newShareDOList = new ArrayList<>();
        for (ShareDO shareDO : shareDOList) {
            String oldFileId = shareDO.getFileId();
            String newFileId = CharSequenceUtil.replace(oldFileId, pathName, finalNewPathName);
            shareDO.setFileId(newFileId);
            if (pathName.endsWith(oldFileId)) {
                shareDO.setFileName(newFilePath.getFileName().toString());
            }
            newShareDOList.add(shareDO);
        }
        if (!newShareDOList.isEmpty()) {
            mongoTemplate.insertAll(newShareDOList);
        }
    }

    /**
     * 删除临时文件，如果有的话
     *
     * @param ossPath    ossPath
     * @param objectName objectName
     */
    private void deleteTemp(String ossPath, String objectName) {
        Path tempFilePath = Paths.get(fileProperties.getRootDir(), ossPath, objectName);
        if (Files.exists(tempFilePath)) {
            PathUtil.del(tempFilePath);
        }
    }

    public void delete(String ossPath, List<String> pathNameList) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        for (String pathName : pathNameList) {
            String objectName = pathName.substring(ossPath.length());
            // 删除对象
            if (ossService.delete(objectName)) {
                notifyDeleteFile(ossPath, objectName);
                // 删除临时文件，如果有的话
                deleteTemp(ossPath, objectName);
                // 删除依赖，如果有的话
                Query query = new Query();
                query.addCriteria(Criteria.where("_id").regex("^" + pathName));
                List<FileDocument> fileDocumentList = mongoTemplate.findAllAndRemove(query, FileDocument.class);
                Query shareQuery = new Query();
                List<String> fileIds = fileDocumentList.stream().map(FileBase::getId).toList();
                shareQuery.addCriteria(Criteria.where("fileId").in(fileIds));
                mongoTemplate.remove(shareQuery, ShareDO.class);
            }
        }
    }

    public ResponseEntity<Object> thumbnail(String ossPath, String pathName) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = pathName.substring(ossPath.length());
        File tempFile = Paths.get(fileProperties.getRootDir(), pathName).toFile();
        if (FileUtil.exist(tempFile)) {
            return getResponseEntity(tempFile);
        } else {
            Path tempFileFolderPath = tempFile.toPath().getParent();
            PathUtil.mkdir(tempFileFolderPath);
        }
        ossService.getThumbnail(objectName, tempFile, 256);
        return getResponseEntity(tempFile);
    }

    @NotNull
    private static ResponseEntity<Object> getResponseEntity(File file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "fileName=" + ContentDisposition.builder("attachment")
                        .filename(UriUtils.encode(file.getName(), StandardCharsets.UTF_8)))
                .header(HttpHeaders.CONTENT_TYPE, FileContentTypeUtils.getContentType(FileUtil.getSuffix(file)))
                .header(HttpHeaders.CONNECTION, "close")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(file.length()))
                .header(HttpHeaders.CONTENT_ENCODING, "utf-8")
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800")
                .body(FileUtil.readBytes(file));
    }

    public FileIntroVO addFile(String ossPath, Boolean isFolder, Path prePth) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, isFolder);
        Path tempFileAbsolutePath = Paths.get(fileProperties.getRootDir(), prePth.toString());
        if (ossService.doesObjectExist(objectName)) {
            throw new CommonException(ExceptionType.WARNING.getCode(), "该文件已存在");
        }
        FileInfo fileInfo;
        BucketInfo bucketInfo = CaffeineUtil.getOssDiameterPrefixCache(ossPath);
        Path path = Paths.get(ossPath);
        try {
            if (Boolean.TRUE.equals(isFolder)) {
                ossService.mkdir(objectName);
                fileInfo = BaseOssService.newFileInfo(objectName, bucketInfo.getBucketName());
            } else {
                Path tempFileAbsoluteFolderPath = tempFileAbsolutePath.getParent();
                PathUtil.mkdir(tempFileAbsoluteFolderPath);
                if (!Files.exists(tempFileAbsolutePath)) {
                    Files.createFile(tempFileAbsolutePath);
                }
                ossService.uploadFile(tempFileAbsolutePath, objectName);
                fileInfo = BaseOssService.newFileInfo(objectName, bucketInfo.getBucketName(), tempFileAbsolutePath.toFile());
                Files.delete(tempFileAbsolutePath);
            }
            notifyCreateFile(path.subpath(0, 1).toString(), objectName, bucketInfo.getFolderName());
        } catch (IOException e) {
            log.error(e.getMessage(), e);
            throw new CommonException(ExceptionType.SYSTEM_ERROR.getCode(), "新建文件失败");
        }
        String userId = userService.getUserIdByUserName(path.subpath(0, 1).toString());
        return fileInfo.toFileIntroVO(ossPath, userId);
    }

    public void putObjectText(String ossPath, Path prePth, String contentText) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);
        InputStream inputStream = new ByteArrayInputStream(CharSequenceUtil.bytes(contentText, StandardCharsets.UTF_8));
        ossService.write(inputStream, ossPath, objectName);
        notifyUpdateFile(ossPath, objectName, contentText.length());
    }

    public void download(String ossPath, Path prePth, HttpServletRequest request, HttpServletResponse response) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = getObjectName(prePth, ossPath, false);
        try (AbstractOssObject abstractOssObject = ossService.getAbstractOssObject(objectName);
             InputStream inputStream = abstractOssObject.getInputStream();
             InputStream inStream = new BufferedInputStream(inputStream, 2048);
             OutputStream outputStream = response.getOutputStream()) {
            FileInfo fileInfo = ossService.getFileInfo(objectName);
            String encodedFilename = URLEncoder.encode(fileInfo.getName(), StandardCharsets.UTF_8);
            String suffix = FileUtil.getSuffix(encodedFilename);
            // 设置响应头
            response.setContentType(FileContentTypeUtils.getContentType(suffix));
            long fileSize = abstractOssObject.getContentLength();
            // 处理 Range 请求
            String range = request.getHeader("Range");
            long length = fileSize;
            if (CharSequenceUtil.isNotBlank(range)) {
                response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + encodedFilename);
                response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
                long[] ranges = parseRange(range, length);
                long start = ranges[0];
                long end = ranges[1] == -1 ? fileSize - 1 : ranges[1];
                String contentRange = "bytes " + start + "-" + end + "/" + fileSize;
                response.setHeader("Content-Range", contentRange);
                length = end - start + 1;
                response.setContentLengthLong(length);
                long count = inputStream.skip(start);
                if (count == start) {
                    log.warn("error skip: {}, actual: {}", start, count);
                }
            } else {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentLengthLong(fileSize);
            }
            IoUtil.copy(inStream, outputStream);
            abstractOssObject.closeObject();
        } catch (ClientAbortException ignored) {
            // ignored error
        } catch (IOException e) {
            log.error(e.getMessage());
        }
    }

    private long[] parseRange(String range, long contentLength) {
        String[] parts = range.substring("bytes=".length()).split("-");
        long start = parseLong(parts[0], 0L);
        long end = parseLong(parts.length > 1 ? parts[1] : "", contentLength - 1);
        if (end < start) {
            end = start;
        }
        return new long[]{start, end};
    }

    private long parseLong(String value, long defaultValue) {
        if (value == null || value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public FileDocument getFileDocument(String ossPath, String pathName) {
        IOssService ossService = OssConfigService.getOssStorageService(ossPath);
        String objectName = pathName.substring(ossPath.length());
        try (AbstractOssObject abstractOssObject = ossService.getAbstractOssObject(objectName)) {
            if (abstractOssObject == null) {
                return null;
            }
            FileInfo fileInfo = abstractOssObject.getFileInfo();
            String username = getUsernameByOssPath(ossPath);
            String userId = userService.getUserIdByUserName(username);
            return fileInfo.toFileDocument(ossPath, userId);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }
}
