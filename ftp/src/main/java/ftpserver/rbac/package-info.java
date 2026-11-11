/**
 * Phan quyen theo vai tro (RBAC) co scope la duong dan ao, cung cap quyen dac biet tam thoi (break-glass).
 *
 * <p>{@link ftpserver.rbac.RbacService} doc cac bang roles/permissions/role_permissions/user_roles/breakglass_grants
 * qua {@code db}. {@code core.ClientSession} nap {@link ftpserver.rbac.RbacService.Grants} luc dang nhap va ket hop
 * voi {@code acl}; lenh SITE trong {@code command} quan ly break-glass.
 */
package ftpserver.rbac;
