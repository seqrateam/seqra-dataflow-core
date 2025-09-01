import SeqraConfigurationDependency.seqraRulesCommon
import SeqraIrDependency.seqra_ir_api_common
import SeqraUtilDependency.seqraUtilCommon
import org.seqra.common.KotlinDependency

plugins {
    id("kotlin-conventions")
    kotlinSerialization()
}

dependencies {
    implementation(seqraUtilCommon)
    implementation(seqraRulesCommon)

    implementation(KotlinDependency.Libs.kotlinx_coroutines_core)
    implementation(KotlinDependency.Libs.kotlin_logging)

    api(seqra_ir_api_common)
    api(Libs.sarif4k)

    implementation(KotlinDependency.Libs.kotlinx_collections)

    implementation(Libs.fastutil)
}
