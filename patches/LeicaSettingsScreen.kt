package com.hinnka.mycamera.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hinnka.mycamera.raw.LeicaConfig
import com.hinnka.mycamera.raw.LeicaRuntimeState

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LeicaSettingsScreen — Menu do fork Leica Perfect
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * UI limpa e direta — só o que importa pro usuário:
 *   - ONE-CLICK MAX preset button (v6.4.0) — aplica mode_max + Leica M9 CCD pronto pra foto
 *   - 2 Capture Modes (Disparo Rápido / Quality Max) + 5 Melhores LUTs (v6.4.0)
 *
 * v6.4.0 (Cron 7+8+9):
 *   - mode_balanced REMOVIDO — substituído por mode_fast (disparo rápido inteligente).
 *   - RAW/DNG export DESATIVADO (user quer JPEG one-click).
 *   - 5 Melhores LUTs adicionados ao mod menu (Leica M9, Hasselblad, Fuji CC, Fuji NC, CineStill 800T).
 *   - LUT picker bug FIXED — runtimeLutOverride agora respeitado (P-63) — antes ficava stuck on m9 CCD.
 *   - ONE-CLICK MAX button (P-65) — preset rápido pra foto máxima qualidade.
 */

// ── 2 Capture Modes — descrições limpas, sem jargão técnico ──────────
private data class CaptureModeOption(
    val id: String,
    val label: String,
    val desc: String
)

private val CAPTURE_MODES = listOf(
    CaptureModeOption("mode_fast", "Disparo Rápido", "Inteligente — 100% dos casos, ação, burst, casual"),
    CaptureModeOption("mode_max", "Quality Max", "Baixa luz, tripé, máxima qualidade (lento)")
)

// ── 5 Melhores LUTs — curados pra mod menu (v6.4.0) ──────────────────
private data class LutOption(
    val id: String,
    val label: String,
    val desc: String
)

private val BEST_LUTS = listOf(
    LutOption("leica_m9", "Leica M9 CCD", "Neutro quente — diário, street, documental"),
    LutOption("Hasselblad", "Hasselblad HNCS", "Natural — retrato premium, moda, produto"),
    LutOption("cc", "Fuji Classic Chrome", "Vintage saturação baixa — urbano, street"),
    LutOption("nc", "Fuji Classic Neg", "Quente golden hour — retrato, lifestyle"),
    LutOption("film_cinestill_800t", "CineStill 800T", "Túngsten — noite, neon, long exposure")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeicaSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentCaptureMode by remember { mutableStateOf(LeicaConfig.activeCaptureMode) }
    var currentLutId by remember { mutableStateOf(LeicaConfig.runtimeLutOverride ?: LeicaConfig.activeLutId) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color(0xFF0D0D0D),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Leica Perfect",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ─── One-Click MAX preset (v6.4.0) ───────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        currentCaptureMode = "mode_max"
                        currentLutId = "leica_m9"
                        LeicaRuntimeState.setCaptureMode("mode_max")
                        LeicaRuntimeState.setRuntimeLutOverride("leica_m9")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "ONE-CLICK MAX",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Text(
                    text = "Aplica mode_max + Leica M9 CCD + JPEG Q100 — pronto pra foto",
                    color = Color(0x60FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }

            // ─── Capture Mode ──────────────────────────────────────────
            item {
                SectionHeader("Capture Mode")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        CAPTURE_MODES.forEachIndexed { idx, mode ->
                            val isSelected = currentCaptureMode == mode.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentCaptureMode = mode.id
                                        LeicaRuntimeState.setCaptureMode(mode.id)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionDot(isSelected)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = mode.label,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = mode.desc,
                                        color = Color(0x80FFFFFF),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (idx < CAPTURE_MODES.size - 1) {
                                androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }

            // ─── 5 Melhores LUTs (v6.4.0) ────────────────────────────
            item {
                SectionHeader("Look (LUT)")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        BEST_LUTS.forEachIndexed { idx, lut ->
                            val isSelected = currentLutId == lut.id
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentLutId = lut.id
                                        LeicaRuntimeState.setRuntimeLutOverride(lut.id)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SelectionDot(isSelected)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = lut.label,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = lut.desc,
                                        color = Color(0x80FFFFFF),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            if (idx < BEST_LUTS.size - 1) {
                                androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.08f))
                            }
                        }
                        // ─── Reset option ─────────────────────────────
                        androidx.compose.material3.Divider(color = Color.White.copy(alpha = 0.08f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    currentLutId = ""
                                    LeicaRuntimeState.setRuntimeLutOverride(null)
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SelectionDot(currentLutId.isEmpty())
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Reset (usar profile default)",
                                color = Color(0x80FFFFFF),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFFF6B35),
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
private fun SelectionDot(isSelected: Boolean) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(
                if (isSelected) Color(0xFFFF6B35) else Color.Transparent,
                shape = androidx.compose.foundation.shape.CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
