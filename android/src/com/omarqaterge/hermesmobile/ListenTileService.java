package com.omarqaterge.hermesmobile;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile "Hermes listen": tap it to start the voice listener (a fallback trigger). */
public class ListenTileService extends TileService {
    @Override
    public void onStartListening() {
        Tile t = getQsTile();
        if (t != null) {
            t.setState(Tile.STATE_INACTIVE);
            t.updateTile();
        }
    }

    @Override
    public void onClick() {
        // On a locked phone Android asks for the unlock first; listening starts after it.
        if (isLocked()) unlockAndRun(this::fire);
        else fire();
    }

    private void fire() {
        HermesService.start(this);
        HermesService.listenAsync();
    }
}
