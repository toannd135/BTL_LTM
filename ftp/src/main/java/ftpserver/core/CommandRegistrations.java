package ftpserver.core;

import ftpserver.command.AuthCommandHandler;
import ftpserver.command.FeatCommandHandler;
import ftpserver.command.MdtmCommandHandler;
import ftpserver.command.OptsCommandHandler;
import ftpserver.command.PbszCommandHandler;
import ftpserver.command.ProtCommandHandler;
import ftpserver.command.ReinCommandHandler;
import ftpserver.command.RestCommandHandler;
import ftpserver.command.RnfrCommandHandler;
import ftpserver.command.RntoCommandHandler;
import ftpserver.command.SiteCommandHandler;
import ftpserver.command.SizeCommandHandler;
import ftpserver.command.CdupCommandHandler;
import ftpserver.command.CwdCommandHandler;
import ftpserver.command.DeleCommandHandler;
import ftpserver.command.ListCommandHandler;
import ftpserver.command.MkdCommandHandler;
import ftpserver.command.NlstCommandHandler;
import ftpserver.command.NoopCommandHandler;
import ftpserver.command.PassCommandHandler;
import ftpserver.command.PasvCommandHandler;
import ftpserver.command.PortCommandHandler;
import ftpserver.command.PwdCommandHandler;
import ftpserver.command.QuitCommandHandler;
import ftpserver.command.RetrCommandHandler;
import ftpserver.command.RmdCommandHandler;
import ftpserver.command.StorCommandHandler;
import ftpserver.command.SystCommandHandler;
import ftpserver.command.TypeCommandHandler;
import ftpserver.command.UserCommandHandler;

public final class CommandRegistrations {

    private CommandRegistrations() {}

    public static void registerAll(CommandRegistry registry) {
        registerCoreCommands(registry);
        registerAuthAndFsCommands(registry);
        registerDataCommands(registry);
    }

    private static void registerCoreCommands(CommandRegistry registry) {
        registry.register("QUIT", new QuitCommandHandler());
        registry.register("SYST", new SystCommandHandler());
        registry.register("NOOP", new NoopCommandHandler());
        registry.register("FEAT", new FeatCommandHandler());
        registry.register("OPTS", new OptsCommandHandler());
        registry.register("AUTH", new AuthCommandHandler());
        registry.register("PBSZ", new PbszCommandHandler());
        registry.register("PROT", new ProtCommandHandler());
    }

    private static void registerAuthAndFsCommands(CommandRegistry registry) {
        registry.register("USER", new UserCommandHandler());
        registry.register("PASS", new PassCommandHandler());
        registry.register("REIN", new ReinCommandHandler());
        registry.register("PWD", new PwdCommandHandler());
        registry.register("CWD", new CwdCommandHandler());
        registry.register("CDUP", new CdupCommandHandler());
        registry.register("MKD", new MkdCommandHandler());
        registry.register("RMD", new RmdCommandHandler());
        registry.register("DELE", new DeleCommandHandler());
        registry.register("TYPE", new TypeCommandHandler());
        registry.register("SIZE", new SizeCommandHandler());
        registry.register("MDTM", new MdtmCommandHandler());
        registry.register("RNFR", new RnfrCommandHandler());
        registry.register("RNTO", new RntoCommandHandler());
        registry.register("SITE", new SiteCommandHandler());
    }

    private static void registerDataCommands(CommandRegistry registry) {
        registry.register("PASV", new PasvCommandHandler());
        registry.register("PORT", new PortCommandHandler());
        registry.register("LIST", new ListCommandHandler());
        registry.register("NLST", new NlstCommandHandler());
        registry.register("RETR", new RetrCommandHandler());
        registry.register("STOR", new StorCommandHandler(false));
        registry.register("APPE", new StorCommandHandler(true));
        registry.register("REST", new RestCommandHandler());
    }
}
