/* 
Destroy the dev03 workspace, handle, and shock databases.

*/

module WipeDev03 {

	authentication required;
	
	funcdef wipe_dev03() returns(int err_code, string output);
	funcdef shut_down_workspace() returns(int err_code, string output);
	funcdef restart_workspace() returns(int err_code, string output);
	
};