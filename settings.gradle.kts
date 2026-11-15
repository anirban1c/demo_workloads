rootProject.name = "demo-workloads"

include(
    "libs:common",
    "services:gateway",
    "services:login-service",
    "services:funds-service",
    "services:balance-service",
    "services:user-service",
    "services:profile-service",
    "services:insights-service",
    "load-tests",
)
