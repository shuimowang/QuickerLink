package app.quickerlink.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.InstallMobile
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.quickerlink.AppUpdateState
import app.quickerlink.BuildConfig
import app.quickerlink.QuickerUiState
import app.quickerlink.R

internal object ProductLinks {
    const val PROJECT = "https://github.com/shuimowang/QuickerLink"
    const val COMPANION_ACTION =
        "https://getquicker.net/Sharedaction?code=b02b2732-f087-4e45-416d-08deee3e76ba"
    const val FEEDBACK =
        "https://github.com/shuimowang/QuickerLink/issues/new?template=bug_report.yml"
    const val AUTHOR =
        "https://getquicker.net/User/Actions/743590-%E5%9B%B0%E5%9B%B0%E5%90%9B"
}

@Composable
internal fun AboutScreen(
    state: QuickerUiState,
    contentPadding: PaddingValues,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = contentPadding.calculateTopPadding() + 16.dp,
            end = 20.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            BrandHeader(
                versionName = state.appVersionName,
                iteration = BuildConfig.VERSION_CODE,
            )
        }
        item { HorizontalDivider() }
        item { AboutSectionTitle("更新") }
        item {
            UpdateSection(
                state = state.updateState,
                currentVersionName = state.appVersionName,
                onCheckForUpdates = onCheckForUpdates,
                onDownloadAndInstall = onDownloadAndInstall,
                onInstallUpdate = onInstallUpdate,
                onOpenRelease = onOpenExternalUrl,
            )
        }
        item {
            Text(
                "不会后台检查或自动下载；仅在你主动操作时访问 GitHub",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Spacer(Modifier.height(2.dp))
            HorizontalDivider()
        }
        item { AboutSectionTitle("项目与作者") }
        item {
            AboutLinkRow(
                icon = Icons.Outlined.Link,
                title = "项目地址",
                detail = "github.com/shuimowang/QuickerLink",
                accessibilityLabel = "在浏览器打开 Quicker Link 项目地址",
                onClick = { onOpenExternalUrl(ProductLinks.PROJECT) },
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(start = 44.dp)) }
        item {
            AboutLinkRow(
                icon = Icons.Outlined.Person,
                title = "发布人",
                detail = "困困君",
                accessibilityLabel = "在浏览器打开困困君的 Quicker 主页",
                onClick = { onOpenExternalUrl(ProductLinks.AUTHOR) },
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(start = 44.dp)) }
        item {
            AboutLinkRow(
                icon = Icons.Outlined.FavoriteBorder,
                title = "支持作者",
                detail = "看看困困君的更多 Quicker 动作",
                accessibilityLabel = "在浏览器打开作者主页以支持作者",
                onClick = { onOpenExternalUrl(ProductLinks.AUTHOR) },
            )
        }
        item { HorizontalDivider(modifier = Modifier.padding(start = 44.dp)) }
        item {
            AboutLinkRow(
                icon = Icons.Outlined.Edit,
                title = "反馈建议",
                detail = "欢迎告诉我你的想法",
                accessibilityLabel = "在浏览器打开 GitHub 反馈页面",
                onClick = { onOpenExternalUrl(ProductLinks.FEEDBACK) },
            )
        }
        item {
            Text(
                "感谢使用 Quicker Link，也欢迎支持作者和提出建议。",
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BrandHeader(
    versionName: String,
    iteration: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            modifier = Modifier.size(68.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.padding(2.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Quicker Link",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "版本 v$versionName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "已迭代 $iteration 次 · 困困君持续打磨",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun UpdateSection(
    state: AppUpdateState,
    currentVersionName: String,
    onCheckForUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    when (state) {
        AppUpdateState.Idle -> OutlinedButton(
            onClick = onCheckForUpdates,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Sync, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("手动检查更新")
        }

        AppUpdateState.Checking -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text("正在检查更新", style = MaterialTheme.typography.bodyLarge)
        }

        AppUpdateState.UpToDate -> UpdateResultRow(
            icon = Icons.Outlined.CheckCircle,
            title = "已是最新版本",
            detail = "当前版本 v$currentVersionName",
            actionLabel = "重新检查",
            onAction = onCheckForUpdates,
        )

        is AppUpdateState.Available -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("发现新版本", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "当前 v$currentVersionName，可更新至 v${state.release.versionName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Button(
                    onClick = onDownloadAndInstall,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("下载并安装")
                }
                TextButton(
                    onClick = { onOpenRelease(state.release.pageUrl) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("浏览器打开发布页")
                }
                Text(
                    "下载完成并通过安全校验后，将由 Android 系统安装器请你确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }

        is AppUpdateState.Downloading -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "正在下载 v${state.release.versionName}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${state.percent.coerceIn(0, 100)}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                LinearProgressIndicator(
                    progress = { state.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "请保持网络连接。下载只会在你手动发起后进行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is AppUpdateState.Verifying -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("正在安全校验安装包", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "正在核对文件完整性、应用身份、版本和签名；只有全部通过才会交给系统安装器。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is AppUpdateState.ReadyToInstall -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("安装包已准备好", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "v${state.install.release.versionName} 已通过完整性、应用身份和签名校验",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Button(
                    onClick = onInstallUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.InstallMobile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开系统安装器")
                }
                Text(
                    "Android 会显示安装确认；首次使用时可能需要授权“安装未知应用”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
            }
        }

        is AppUpdateState.Failed -> if (state.release == null) {
            UpdateResultRow(
                icon = Icons.Outlined.ErrorOutline,
                title = "检查失败",
                detail = state.message,
                actionLabel = "重试",
                onAction = onCheckForUpdates,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("下载或校验失败", style = MaterialTheme.typography.titleMedium)
                            Text(state.message, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Button(
                        onClick = onDownloadAndInstall,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("重新下载并安装")
                    }
                    TextButton(
                        onClick = { onOpenRelease(state.release.pageUrl) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("浏览器打开发布页")
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateResultRow(
    icon: ImageVector,
    title: String,
    detail: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    title: String,
    detail: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = accessibilityLabel,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}
