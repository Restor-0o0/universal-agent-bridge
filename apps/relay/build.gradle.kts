plugins { application; alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":relay-engine"))
    testImplementation(kotlin("test"))
}

application { mainClass.set("dev.agentbridge.relay.MainKt") }
