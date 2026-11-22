; LaserDroid test: 20 mm square, starts at X5 Y5
; Intended for a first run with "Laser OFF" checked.
G21
G90
M4 S0
G0 X5 Y5
G1 X25 Y5 S100 F1000
G1 X25 Y25
G1 X5 Y25
G1 X5 Y5
M5
G0 X0 Y0
