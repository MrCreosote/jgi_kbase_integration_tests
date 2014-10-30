/* 
Destroy the dev03 workspace, handle, and shock databases.

*/

module WipeDev03 {

	funcdef wipe_dev03() returns(int err_code, string output)
		authentication required;
};