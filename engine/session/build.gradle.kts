plugins { id("ravenemu.jvm") }

dependencies {
    api(project(":engine:api"))
    implementation(project(":engine:state"))
    testImplementation(project(":engine:cheats"))
}
