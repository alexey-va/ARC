# ArcCore Paper provider host

This subproject packages the `ArcCore` Paper plugin. It owns the SQL, Redis,
and consumer-metrics providers from the published `arc-core` modules and exposes
them through Bukkit's `ServicesManager`. ARC depends on this plugin and retains
its Redis configuration and Prometheus registry.

This is a provider host, not a feature consumer. Its manifest therefore declares
no consumer capabilities; the generic Paper consumer baseline does not describe
this plugin's role.
