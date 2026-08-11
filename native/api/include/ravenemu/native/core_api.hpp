#pragma once
#include <ravenemu/core.hpp>
#include <ravenemu/cheats.hpp>

namespace ravenemu::native_api {
using Core = ravenemu::Core;
using Console = ravenemu::Console;
using Button = ravenemu::Button;
using BatterySnapshot = ravenemu::BatterySnapshot;
using GbaDebugSnapshot = ravenemu::GbaDebugSnapshot;
using GbaSaveType = ravenemu::GbaSaveType;
using RomLoadError = ravenemu::RomLoadError;
using SaveStateError = ravenemu::SaveStateError;
using CheatCapableCore = ravenemu::CheatCapableCore;
using CheatCode = ravenemu::CheatCode;
using CheatFormat = ravenemu::CheatFormat;
}
