package com.jmal.clouddisk.service;

import com.jmal.clouddisk.model.ShareDO;
import com.jmal.clouddisk.model.SharerDTO;
import com.jmal.clouddisk.model.UploadApiParamDTO;
import com.jmal.clouddisk.model.rbac.ConsumerDO;
import com.jmal.clouddisk.util.ResponseResult;
import jakarta.servlet.http.HttpServletRequest;

import java.util.List;

/**
 * @Description IShareService
 * @Author jmal
 * @Date 2020-03-17 16:21
 */
public interface IShareService {

    /**
     * 生成分享链接
     * @param share ShareDO
     * @return ResponseResult
     */
    ResponseResult<Object> generateLink(ShareDO share);

    /**
     * 访问分享链接
     * @param shareDO ShareDO
     * @param pageIndex pageIndex
     * @param pageSize pageSize
     * @return 文件列表
     */
    ResponseResult<Object> accessShare(ShareDO shareDO, Integer pageIndex, Integer pageSize);

    /**
     * 获取分享信息
     * @param share 分享链接
     * @return ShareDO
     */
    ShareDO getShare(String share);

    /**
     * 打开目录
     * @param share ShareDO
     * @param fileId fileId
     * @param pageIndex pageIndex
     * @param pageSize pageSize
     * @return ResponseResult
     */
    ResponseResult<Object> accessShareOpenDir(ShareDO share, String fileId, Integer pageIndex, Integer pageSize);

    /**
     * 获取分享列表
     * @param upload UploadApiParamDTO
     * @return List<ShareDO>
     */
    List<ShareDO> getShareList(UploadApiParamDTO upload);

    /**
     * 分享列表
     * @param upload UploadApiParamDTO
     * @return ResponseResult
     */
    ResponseResult<Object> shareList(UploadApiParamDTO upload);

    /**
     * 取消分享
     * @param shareIdList shareIdList
     * @return ResponseResult
     */
    ResponseResult<Object> cancelShare(String[] shareIdList);

    /**
     * 删除关联分享
     * @param userList userList
     */
    void deleteAllByUser(List<ConsumerDO> userList);

    /***
     * 获取分享者信息
     * @param shareId shareId
     * @return ResponseResult
     */
    ResponseResult<SharerDTO> getSharer(String shareId);

    /**
     * 验证提取码
     * @param shareId shareId
     * @param shareCode 提取码
     * @return ResponseResult
     */
    ResponseResult<Object> validShareCode(String shareId, String shareCode);

    void validShareCode(String shareToken, ShareDO shareDO);

    void validShare(String shareToken, ShareDO shareDO);

    void validShare(String shareToken, String shareId);

    void validShare(HttpServletRequest request);

    /**
     * 挂载文件
     * @param upload UploadApiParamDTO
     */
    void mountFile(UploadApiParamDTO upload);
}
