type kind = List_papers | Search_papers | Show_paper | Import_papers | Update_paper | Attach_pdf

type parsed = {
  kind : kind;
  uuid : string option;
  content : string option;
  input : string option;
  stdin : string option;
  target_page : string option;
  asset_uuid : string option;
  path : string option;
  dry_run : bool;
  limit : int option;
  before : int64 option;
}

type action = {
  repo : Cli_primitive.repo;
  request : parsed;
  records : Melange_edn_melange.any Rrbvec.t;
}

val kind_of_string : string -> kind option
include Command_spec.S with type parsed := parsed and type action := action
