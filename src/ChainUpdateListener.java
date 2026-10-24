import java.util.List;

@FunctionalInterface
public interface ChainUpdateListener {
    void onChainUpdated(List<Block> newChainSnapshot);
}