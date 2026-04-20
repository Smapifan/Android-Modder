namespace AndroidModder.Mods;

public interface IGamePatch
{
    string GameId { get; }
    void Apply(string workspaceRoot);
}

public sealed class SampleMergeDragonsPatch : IGamePatch
{
    public string GameId => "MergeDragons";

    public void Apply(string workspaceRoot)
    {
        // Placeholder: extension-provided patch logic runs against exported save/workspace data.
    }
}
