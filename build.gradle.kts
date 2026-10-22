import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "dev.aicommit"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Compile against the 2024.2 platform: it is the oldest platform that
        // still ships the commit workflow UI this plugin extends.
        intellijIdeaCommunity("2024.2.5")
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }

    // Gson is bundled with every IntelliJ-based IDE. It is only a compile-time
    // dependency here: the platform plugin classloader provides it at runtime.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    // The IntelliJ test framework's launcher listener references JUnit 4 classes.
    testImplementation("junit:junit:4.13.2")
    // Run the platform's JUnit 3 style BasePlatformTestCase tests on JUnit Platform.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        id = "dev.aicommit.jetbrains"
        name = "AI Git Commit"
        version = project.version.toString()

        description = """
            <p>根据提交面板中选中的改动生成 Git 提交信息，支持任意 OpenAI 兼容模型。插件内置自动管理的
            OpenCode Zen 引擎，默认免费模型无需安装或运行单独网关。</p>
            <ul>
              <li>提交工具栏一键生成，支持快捷键。</li>
              <li>自动读取模型参数（上下文长度、输入/输出上限、推理档位），无需逐个模型手填。</li>
              <li>打开项目和提交前探测模型可用性，自动绕过地区受限、已下架和临时限流的模型。</li>
              <li>支持中文/英文提交信息和插件界面，默认提示词可编辑。</li>
              <li>支持 IntelliJ IDEA、PyCharm、GoLand、WebStorm 等基于 IntelliJ 平台的 IDE。</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = "242"
            // Keep the plugin installable in future IDEs; the plugin verifier
            // checks the binary compatibility against the versions below.
            untilBuild = provider { null }
        }

        vendor {
            name = "AI Git Commit"
        }

        changeNotes = """
            <h3>1.0.1</h3>
            <ul>
              <li>修复：外部工具端点现在兼容尾部斜杠、重复斜杠、重复 /v1 以及客户端重复拼接接口路径的情况。</li>
              <li>修复：未知路径返回带说明的 JSON 404（列出支持的端点），不再只有引擎的 404 page not found。</li>
              <li>新增：外部端点请求日志，记录方法、原始路径、转发路径、状态码和耗时，可在设置页看到文件位置。</li>
            </ul>
            <h3>1.0.0</h3>
            <ul>
              <li>提交工具栏一键生成：支持中文/英文提交信息，界面、提示和错误信息支持中英文切换。</li>
              <li>内置 OpenCode Zen 引擎和默认免费模型，无需单独安装网关；支持模型轮换与失败切换。</li>
              <li>自动从 /v1/models 读取上下文长度、输入/输出上限、推理档位，无需逐个模型配置。</li>
              <li>打开项目和提交前探测模型可用性，自动跳过地区受限、已下架、临时限流/超时的模型。</li>
              <li>支持自定义 OpenAI 兼容端点、API Key、模型、推理级别、输入输出上限和默认提示词。</li>
              <li>勾选未跟踪文件时，先加入 VCS（git add）再生成提交信息。</li>
              <li>支持把免费且可用的模型暴露给其他本地工具：一键复制 AI 端点，模型列表只包含探测可用的免费模型。</li>
            </ul>
        """.trimIndent()
    }

    pluginVerification {
        ides {
            ide("IC", "2024.2.5")
            ide("IU", "2026.2.3")
        }
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks {
    test {
        useJUnitPlatform()
    }
}
