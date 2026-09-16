// Breaks ACL inheritance on a node, in both workspaces, and grants nothing.
//
// This is the shape the visibility check exists to find, and it has to be built
// rather than mocked: in Jahia a server administrator is a ROLE, and a role is
// delivered through ACL entries - exactly what breaking inheritance removes. So
// after this runs, every account except `root` and the system session is denied,
// including site and server administrators. A test that only looks as `root`
// sees nothing wrong here, which is the whole reason this fixture exists.
//
// Both workspaces on purpose. The check reads `live` as guest, so breaking it
// only in `default` would leave the published copy readable and the assertion
// would measure nothing.
//
// @jahia/cypress substitutes NODE_PATH.
import org.jahia.services.content.JCRCallback
import org.jahia.services.content.JCRSessionWrapper
import org.jahia.services.content.JCRTemplate

['default', 'live'].each { workspace ->
    JCRTemplate.instance.doExecuteWithSystemSession(null, workspace, null, { JCRSessionWrapper session ->
        def node = session.nodeExists('NODE_PATH') ? session.getNode('NODE_PATH') : null
        if (node == null) {
            // Not an error: the live copy may not exist yet when this is called
            // before a publish. The caller decides the order.
            return null
        }
        node.setAclInheritanceBreak(true)
        session.save()
        return null
    } as JCRCallback)
}
