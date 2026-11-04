package com.chainsip.chain;

import com.chainsip.config.ChainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * ABI / BIN 资源导出器。
 *
 * <p>FISCO SDK 的合约加载器是按"目录 + 文件"读取 ABI 的，而打包成 jar 后资源在 classpath 里，
 * 因此启动时把 {@code classpath:abi/*.abi} 与 {@code *.bin} 导出到临时目录，再把目录路径交给 SDK。
 */
@Component
public class AbiResourceExporter {

    private static final Logger log = LoggerFactory.getLogger(AbiResourceExporter.class);

    private final ChainProperties properties;
    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    private volatile Path abiDir;
    private volatile Path binDir;

    public AbiResourceExporter(ChainProperties properties) {
        this.properties = properties;
    }

    /** 导出并返回 [abiDir, binDir]。只导出一次，重复调用返回同一目录。 */
    public synchronized Path[] export() {
        if (abiDir != null) {
            return new Path[] {abiDir, binDir};
        }
        try {
            Path base = Files.createTempDirectory("chainsip-contracts-");
            Path abi = Files.createDirectories(base.resolve("abi"));
            Path bin = Files.createDirectories(base.resolve("bin"));
            int abiCount = copyResources(properties.getAbiResourceDir(), "abi", abi);
            int binCount = copyResources(properties.getAbiResourceDir(), "bin", bin);
            this.abiDir = abi;
            this.binDir = bin;
            log.info("合约资源已导出：abi {} 个、bin {} 个 -> {}", abiCount, binCount, base.toAbsolutePath());
            if (abiCount == 0) {
                throw new IllegalStateException("classpath:" + properties.getAbiResourceDir() + " 下没有找到 .abi 文件");
            }
            return new Path[] {abi, bin};
        } catch (IOException e) {
            throw new IllegalStateException("导出 ABI 资源失败: " + e.getMessage(), e);
        }
    }

    private int copyResources(String resourceDir, String extension, Path target) throws IOException {
        Resource[] resources = resolver.getResources("classpath*:" + resourceDir + "/*." + extension);
        int count = 0;
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null) {
                continue;
            }
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, target.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    public Path getAbiDir() {
        return abiDir;
    }
}
