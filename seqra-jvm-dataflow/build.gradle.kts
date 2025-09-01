import SeqraConfigurationDependency.seqraRulesJvm
import SeqraIrDependency.seqra_ir_api_jvm
import SeqraIrDependency.seqra_ir_api_storage
import SeqraIrDependency.seqra_ir_core
import SeqraIrDependency.seqra_ir_storage
import SeqraUtilDependency.seqraUtilCommon
import SeqraUtilDependency.seqraUtilJvm
import org.seqra.common.KotlinDependency

plugins {
    id("kotlin-conventions")
    kotlinSerialization()
}

dependencies {
    api(project(":seqra-dataflow"))
    implementation(seqraUtilCommon)
    implementation(seqraUtilJvm)
    implementation(seqraRulesJvm)

    implementation(seqra_ir_api_jvm)
    implementation(seqra_ir_core)
    implementation(seqra_ir_api_storage)
    implementation(seqra_ir_storage)

    implementation(KotlinDependency.Libs.kotlin_logging)
    implementation(KotlinDependency.Libs.reflect)

    implementation(Libs.fastutil)

    implementation(Libs.sarif4k)
}
