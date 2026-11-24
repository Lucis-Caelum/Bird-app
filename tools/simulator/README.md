# Offline simulator

Compiles the pure-Kotlin core (`app/src/main/java/com/luciscaelum/slayautoplayer/core`) together with these files; no Android SDK needed.

```
kotlinc ../../app/src/main/java/com/luciscaelum/slayautoplayer/core/*.kt Sim.kt RenderSim.kt -include-runtime -d sim.jar
java -cp sim.jar com.luciscaelum.slayautoplayer.core.SimKt 40          # core only: 40 seeds
java -cp sim.jar com.luciscaelum.slayautoplayer.core.SimKt render 24 40 # rendered frames -> analyzer -> pilot
BIRD_DEBUG=1 java -cp sim.jar com.luciscaelum.slayautoplayer.core.SimKt renderseed 21 40  # per-frame trace
```
`SimCfg` holds the world / noise / latency parameters; edit them to match what the log of a real device shows.
