package com.jmal.clouddisk.oss;

import com.jmal.clouddisk.webdav.resource.OssFileResource;
import lombok.Getter;
import lombok.Setter;

import java.io.InputStream;
import java.util.zip.CheckedInputStream;
import java.util.zip.Checksum;

/**
 * @author jmal
 * @Description OssInputStream
 * @date 2023/3/28 16:32
 */
public class OssInputStream extends CheckedInputStream {

    @Getter
    @Setter
    private OssFileResource ossFileResource;

    public OssInputStream(InputStream in, Checksum checksum) {
        super(in, checksum);
    }
}
